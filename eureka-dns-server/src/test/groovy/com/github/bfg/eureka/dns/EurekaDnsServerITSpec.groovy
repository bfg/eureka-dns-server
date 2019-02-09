package com.github.bfg.eureka.dns

import groovy.util.logging.Slf4j
import io.netty.buffer.Unpooled
import org.xbill.DNS.Lookup
import org.xbill.DNS.Name
import org.xbill.DNS.Record
import org.xbill.DNS.Resolver
import org.xbill.DNS.SRVRecord
import org.xbill.DNS.Section
import org.xbill.DNS.SimpleResolver
import org.xbill.DNS.TXTRecord
import spock.lang.AutoCleanup
import spock.lang.Ignore
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

import static org.xbill.DNS.DClass.IN
import static org.xbill.DNS.Lookup.HOST_NOT_FOUND
import static org.xbill.DNS.Lookup.SUCCESSFUL
import static org.xbill.DNS.Type.A
import static org.xbill.DNS.Type.NS
import static org.xbill.DNS.Type.SOA
import static org.xbill.DNS.Type.SRV
import static org.xbill.DNS.Type.TXT

@Slf4j
@Unroll
class EurekaDnsServerITSpec extends Specification {
    @Shared
    def config = TestUtils.defaultConfig()
    @Shared
    def domain = config.getDomain()

    @Shared
    @AutoCleanup
    EurekaDnsServer server = config.create().start().get()

    @Shared
    Resolver resolver = createResolver()

    Resolver createResolver(boolean edns = false) {
        def r = new SimpleResolver("localhost")
        r.setPort(config.getPort())
        r.setTimeout(0, 500)
        r.setIgnoreTruncation(true)
        r.setTCP(false)

        if (edns) {
            r.setEDNS(0)
        }

        r
    }

    Lookup lookup(String name, int type = A) {
        def l = new Lookup(name, type)
        l.setResolver(resolver)
        l.setNdots(0)
        l.setSearchPath([].toArray(new String[0]))
        l
    }

    List<Record> run(Lookup lookup) {
        def result = lookup.run()?.toList() ?: []
        log.info("dns response ({}, {} records): {}", lookup.getErrorString(), result.size(), result)
        result
    }

    @Ignore("this test should be enabled only for manual dig(1) checks")
    def "should run"() {
        when:
        Thread.sleep(300_000)

        then:
        true
    }

    def "should correctly respond to NS query: #name"() {
        given:
        def lookup = lookup(name, NS)

        when:
        def records = run(lookup)

        then:
        lookup.getResult() == SUCCESSFUL
        records.size() == 1

        // inspect answer record
        def record = records.first()
        record.getDClass() == IN
        record.getType() == NS
        record.getTTL() == config.getTtl()
        record.rdataToString() == "ns." + config.getDomain() + "."

        where:
        name << [
                config.getDomain(),
                "service." + config.getDomain(),
                "corse.service." + config.getDomain(),
                "_corse._tcp.service." + config.getDomain(),
        ]
    }

    def "should correctly respond to SOA query: #name"() {
        given:
        def lookup = lookup(name, SOA)

        and:
        def currentTs = System.currentTimeMillis() / 1000

        // SOA serial depends on current unix timestamp, we need to wait until next second
        def unixTs = (int) currentTs
        def leftoverToAnotherSec = (int) (currentTs - unixTs) * 1000
        Thread.sleep(leftoverToAnotherSec + 100)

        def expectedSoa = "^ns.${config.getDomain()}. hostmaster.${config.getDomain()}. \\d+ 3600 600 86400 0\$"

        when:
        def records = run(lookup)

        then:
        lookup.getResult() == SUCCESSFUL
        records.size() == 1

        // inspect answer record
        def record = records.first()
        record.getDClass() == IN
        record.getType() == SOA
        record.getTTL() == config.getTtl()
        record.rdataToString().matches(expectedSoa)

        where:
        name << [
                config.getDomain(),
                "service." + config.getDomain(),
                "corse.service." + config.getDomain(),
                "_corse._tcp.service." + config.getDomain(),
        ]
    }

    def "should correctly respond to TXT query in default region: #name"() {
        given:
        def lookup = lookup(name, TXT)

        when:
        def records = run(lookup)

        then:
        lookup.getResult() == SUCCESSFUL
        records.size() == 4

        records.each { assert it.getTTL() == config.getTtl() }
        records.each { assert it.getName().toString().startsWith(name) }
        records.each { assert it.getDClass() == IN }
        records.each { assert it.getType() == TXT }

        when: "weed out urls"
        def urls = records.collect { ((TXTRecord) it).getStrings().join("") }

        then:
        urls[0] == 'http://host-100.us-west-2.compute.internal:8080/'
        urls[1] == 'http://host-101.us-west-2.compute.internal/'
        urls[2] == 'https://host-102.us-west-2.compute.internal/'
        urls[3] == 'https://host-104.us-west-2.compute.internal:8443/'

        where:
        name << [
                "corse.service.${domain}",
                "corse.service.${domain}.",
                "corse.service.default.${domain}",
                "corse.service.default.${domain}.",
        ]
    }

    def "should correctly respond to TXT query in non-default region: #name"() {
        given:
        def lookup = lookup(name, TXT)

        when:
        def records = run(lookup)

        then:
        lookup.getResult() == SUCCESSFUL
        records.size() == 1

        records.each { assert it.getTTL() == config.getTtl() }
        records.each { assert it.getName().toString().startsWith(name) }
        records.each { assert it.getDClass() == IN }
        records.each { assert it.getType() == TXT }

        // check url
        records[0].getStrings().join("") == "http://host-152.us-west-2.compute.internal:8080/"

        where:
        name << [
                "mallorca.service.dc1.${domain}",
                "mallorca.service.dc1.${domain}.",
        ]
    }

    def "should return empty set of TXT records for unknown service"() {
        def lookup = lookup("foo.service.${domain}.", TXT)

        when:
        def records = run(lookup)

        then:
        lookup.getResult() == HOST_NOT_FOUND
        records.isEmpty()
    }

    //
    // BEGIN: RFC2782
    //

    def "should correctly respond SRV records for: #name"() {
        given:
        def lookup = lookup(name, SRV)

        when:
        def records = run(lookup)
        log.info("records: {}", records)

        then:
        //lookup.getResult() == SUCCESSFUL
        records.size() == 4

        records.each { assert it.getTTL() == config.getTtl() }
        records.each { assert it.getName().toString().startsWith(name) }
        records.each { assert it.getDClass() == IN }
        records.each { assert it.getType() == SRV }

        where:
        name << [
                "corse.service.${domain}",
//                "corse.service.default.${domain}",

//                "_corse._tcp.service.${domain}.",
//                "_corse._tcp.service.default.${domain}",
//                "_corse._tcp.service.default.${domain}.",
        ]
    }

    def foo() {
        given:
        def name = new Name("_http._tcp.corse.service.${domain}.")
        def target = new Name("foo.bar.baz.");
        def srv = new SRVRecord(name, IN, 15, 2, 3, 8080, target)

        log.info("{}", srv)
        def bytes = srv.toWire(Section.ANSWER)
        log.info("bytes: {}", bytes.length)

        def buf = Unpooled.buffer()
        buf.writeBytes(bytes)

        log.info("buf: {} {}", buf, buf.readableBytes())

        log.info("bytes: {}", bytes)

        expect:
        true
    }

    //
    // END:   RFC2782
    //
}
