package com.github.bfg.eureka.dns

import groovy.util.logging.Slf4j
import org.xbill.DNS.Lookup
import org.xbill.DNS.Record
import org.xbill.DNS.Resolver
import org.xbill.DNS.SimpleResolver
import org.xbill.DNS.TXTRecord
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

import static org.xbill.DNS.DClass.IN
import static org.xbill.DNS.Lookup.HOST_NOT_FOUND
import static org.xbill.DNS.Type.A
import static org.xbill.DNS.Type.TXT

@Slf4j
@Unroll
class EurekaDnsServerITSpec extends Specification {
    static def config = TestUtils.defaultConfig()

    @Shared
    @AutoCleanup
    EurekaDnsServer server = config.create().start().get()

    @Shared
    Resolver resolver = createResolver()

    Resolver createResolver() {
        def r = new SimpleResolver("localhost")
        r.setPort(config.getPort())
        r
    }

    Lookup lookup(String name, int type = A) {
        def l = new Lookup(name, type)
        l.setResolver(resolver)
        l
    }

    List<Record> run(Lookup lookup) {
        def result = lookup.run()?.toList() ?: []
        log.info("dns response ({}, {} records): {}", lookup.getErrorString(), result.size(), result)
        result
    }

    def "should correctly respond to TXT query in default region: #name"() {
        given:
        def lookup = lookup(name, TXT)

        when:
        def records = run(lookup)

        then:
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
                "corse.service.eureka",
                "corse.service.eureka.",
                "corse.service.default.eureka",
                "corse.service.default.eureka.",
        ]
    }

    def "should correctly respond to TXT query in non-default region: #name"() {
        given:
        def lookup = lookup(name, TXT)

        when:
        def records = run(lookup)

        then:
        records.each { assert it.getTTL() == config.getTtl() }
        records.each { assert it.getName().toString().startsWith(name) }
        records.each { assert it.getDClass() == IN }
        records.each { assert it.getType() == TXT }

        // check url
        records[0].getStrings().join("") == "http://host-152.us-west-2.compute.internal:8080/"

        where:
        name << [
                "mallorca.service.dc1.eureka",
                "mallorca.service.dc1.eureka.",
        ]
    }

    def "should return empty set of TXT records for unknown service"() {
        def lookup = lookup("foo.service.eureka.", TXT)

        when:
        def records = run(lookup)

        then:
        records.isEmpty()
        lookup.getResult() == HOST_NOT_FOUND
    }
}
