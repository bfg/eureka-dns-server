package com.github.bfg.eureka.dns

import com.google.common.net.InetAddresses
import com.netflix.discovery.EurekaClient
import groovy.util.logging.Slf4j
import io.netty.handler.codec.dns.DatagramDnsQuery
import io.netty.handler.codec.dns.DatagramDnsResponse
import io.netty.handler.codec.dns.DefaultDnsQuestion
import io.netty.handler.codec.dns.DnsQuestion
import io.netty.handler.codec.dns.DnsRecord
import io.netty.handler.codec.dns.DnsRecordType
import io.netty.handler.codec.dns.DnsResponseCode
import io.netty.handler.codec.dns.DnsSection
import spock.lang.Specification
import spock.lang.Unroll

import java.util.concurrent.atomic.AtomicInteger

import static io.netty.handler.codec.dns.DnsRecord.CLASS_ANY
import static io.netty.handler.codec.dns.DnsRecord.CLASS_CHAOS
import static io.netty.handler.codec.dns.DnsRecord.CLASS_CSNET
import static io.netty.handler.codec.dns.DnsRecord.CLASS_HESIOD
import static io.netty.handler.codec.dns.DnsRecord.CLASS_NONE
import static io.netty.handler.codec.dns.DnsRecordType.A
import static io.netty.handler.codec.dns.DnsRecordType.AAAA
import static io.netty.handler.codec.dns.DnsResponseCode.BADNAME
import static io.netty.handler.codec.dns.DnsResponseCode.NXDOMAIN
import static io.netty.handler.codec.dns.DnsResponseCode.REFUSED

@Slf4j
@Unroll
class DnsQueryHandlerSpec extends Specification {
    private static final def counter = new AtomicInteger()
    def clientAddr = new InetSocketAddress(InetAddresses.forString("2a01:260:d001:e744:a1d9:a2b1:c72e:8cfc"), 32456)
    def serverAddr = new InetSocketAddress(InetAddresses.forString("2a01:260:d001:e744::53"), 5353)

    def eurekaClient = newEurekaClient()
    def handler = new DnsQueryHandler(newConfig(eurekaClient))
    def query = new DatagramDnsQuery(clientAddr, serverAddr, counter.incrementAndGet())

    def "should throw in case of null arguments"() {
        when:
        new DnsQueryHandler(null)

        then:
        thrown(NullPointerException)
    }

    def "getDatacenter(#name) should return #expected"() {
        expect:
        handler.getDatacenter(name) == expected

        where:
        name                                 | expected
        ""                                   | ""
        "  "                                 | ""

        "foo.service.eureka"                 | ""
        "foo.node.eureka"                    | ""
        "foo.connect.eureka"                 | ""
        "_http._tcp.foo.service.eureka"      | ""
        "_http._tcp.foo.node.eureka"         | ""
        "_http._tcp.foo.connect.eureka"      | ""

        "foo.service.eureka."                | ""
        "foo.node.eureka."                   | ""
        "foo.connect.eureka."                | ""
        "_http._tcp.foo.service.eureka."     | ""
        "_http._tcp.foo.node.eureka."        | ""
        "_http._tcp.foo.connect.eureka."     | ""

        "foo.service.Dc1.eureka"             | "dc1"
        "foo.node.Dc1.eureka"                | "dc1"
        "foo.connect.Dc1.eureka"             | "dc1"
        "_http._tcp.foo.service.Dc1.eureka"  | "dc1"
        "_http._tcp.foo.node.Dc1.eureka"     | "dc1"
        "_http._tcp.foo.connect.Dc1.eureka"  | "dc1"

        "foo.service.DC1.eureka."            | "dc1"
        "foo.node.Dc1.eureka."               | "dc1"
        "foo.connect.Dc1.eureka."            | "dc1"
        "_http._tcp.foo.service.Dc1.eureka." | "dc1"
        "_http._tcp.foo.node.Dc1.eureka."    | "dc1"
        "_http._tcp.foo.connect.Dc1.eureka." | "dc1"

        "foo.service.DC 1.eureka."           | ""
        "foo.service. DC1.eureka."           | ""
        "foo.service.DC1 .eureka."           | ""
        "foo.service. DC1 .eureka."          | ""
    }

    def "getServiceName(#name) should return #expected"() {
        expect:
        handler.getServiceName(name) == expected

        where:
        name                                 | expected
        ""                                   | ""
        "  "                                 | ""

        "foo.service.eureka"                 | "foo"
        "foo.node.eureka"                    | "foo"
        "foo.connect.eureka"                 | "foo"
        "_http._tcp.foo.service.eureka"      | "foo"
        "_http._tcp.foo.node.eureka"         | "foo"
        "_http._tcp.foo.connect.eureka"      | "foo"

        "foo.service.eureka."                | "foo"
        "foo.node.eureka."                   | "foo"
        "foo.connect.eureka."                | "foo"
        "_http._tcp.foo.service.eureka."     | "foo"
        "_http._tcp.foo.node.eureka."        | "foo"
        "_http._tcp.foo.connect.eureka."     | "foo"

        "foo.service.Dc1.eureka"             | "foo"
        "foo.node.Dc1.eureka"                | "foo"
        "foo.connect.Dc1.eureka"             | "foo"
        "_http._tcp.foo.service.Dc1.eureka"  | "foo"
        "_http._tcp.foo.node.Dc1.eureka"     | "foo"
        "_http._tcp.foo.connect.Dc1.eureka"  | "foo"

        "foo.service.DC1.eureka."            | "foo"
        "foo.node.Dc1.eureka."               | "foo"
        "foo.connect.Dc1.eureka."            | "foo"
        "_http._tcp.foo.service.Dc1.eureka." | "foo"
        "_http._tcp.foo.node.Dc1.eureka."    | "foo"
        "_http._tcp.foo.connect.Dc1.eureka." | "foo"

        "foo.service.DC 1.eureka."           | ""
        "foo.service. DC1.eureka."           | ""
        "foo.service.DC1 .eureka."           | ""
        "foo.service. DC1 .eureka."          | ""
    }

    def "should respond with REFUSED to any query that is not IN-class"() {
        given:
        def question = createDnsQuestion("foo.eureka", A, dnsclass)
        def query = createDnsQuery(question)

        when:
        def response = handler.respondToDnsQuery(query)

        then: "handler should refuse to respond to such questions"
        response.code() == DnsResponseCode.REFUSED

        response.count(DnsSection.ADDITIONAL) == 0
        response.recordAt(DnsSection.QUESTION) == question

        response.count(DnsSection.ANSWER) == 0

        where:
        dnsclass << [CLASS_CHAOS, CLASS_CSNET, CLASS_ANY, CLASS_HESIOD, CLASS_NONE]
    }

    def "should respond with #expectedCode for: #type #name"() {
        given:
        def question = createDnsQuestion(name, type)
        def query = createDnsQuery(question)

        when:
        def response = handler.respondToDnsQuery(query)

        then:
        assertResponse(response, question, expectedCode, 1)

        where:
        type | name                  | expectedCode
        // questions that should result in no answers
        //A    | null       | BADNAME
        A    | ""                    | REFUSED
        A    | "  "                  | REFUSED
        A    | "foo"                 | REFUSED
        A    | "foo.bar."            | REFUSED
        A    | "bar"                 | REFUSED
        A    | "bar.service.eureka"  | NXDOMAIN
        A    | "bar .service.eureka" | BADNAME

        //AAAA | null       | BADNAME
        AAAA | ""                    | REFUSED
        AAAA | "  "                  | REFUSED
        AAAA | "foo.service.eureka"  | NXDOMAIN
        AAAA | "foo.bar.eureka"      | BADNAME
        AAAA | "bar.bar.eureka."     | BADNAME
    }

    def "should refuse to answer for valid name with unhandled record type: #type"() {
        given:
        def question = createDnsQuestion("foo.eureka", type)
        def query = createDnsQuery(question)

        when:
        def response = handler.respondToDnsQuery(query)

        then:
        assertResponse(response, question, REFUSED)

        where:
        type << [DnsRecordType.AXFR, DnsRecordType.SPF, DnsRecordType.SOA]
    }

    def assertResponse(DatagramDnsResponse response,
                       DnsQuestion question,
                       DnsResponseCode expectedCode = DnsResponseCode.NOERROR,
                       int numAnswers = 1) {
        assert response.code() == expectedCode
        assert response.recipient() == clientAddr
        assert response.sender() == serverAddr

        assert response.recordAt(DnsSection.QUESTION) == question

        if (expectedCode == DnsResponseCode.NOERROR) {
            assert response.count(DnsSection.ANSWER) == numAnswers
        }

        true
    }

    DnsServerConfig newConfig(EurekaClient client = eurekaClient) {
        new DnsServerConfig().setEurekaClient(client)
                             .setLogQueries(true)
    }

    EurekaClient newEurekaClient() {
        FakeEurekaClient.defaults()
    }

    DnsQuestion createDnsQuestion(
            String name,
            DnsRecordType type = A,
            int dnsclass = DnsRecord.CLASS_IN) {
        new DefaultDnsQuestion(name, type, dnsclass)
    }

    DatagramDnsQuery createDnsQuery(DnsQuestion question) {
        def query = new DatagramDnsQuery(clientAddr, serverAddr, counter.incrementAndGet())
        query.addRecord(DnsSection.QUESTION, question)
        query
    }
}
