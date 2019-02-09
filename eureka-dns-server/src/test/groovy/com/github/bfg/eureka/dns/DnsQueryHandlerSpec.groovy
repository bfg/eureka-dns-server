package com.github.bfg.eureka.dns

import com.google.common.net.InetAddresses
import com.netflix.discovery.EurekaClient
import groovy.util.logging.Slf4j
import io.netty.buffer.ByteBuf
import io.netty.handler.codec.dns.DatagramDnsQuery
import io.netty.handler.codec.dns.DatagramDnsResponse
import io.netty.handler.codec.dns.DefaultDnsQuestion
import io.netty.handler.codec.dns.DnsQuestion
import io.netty.handler.codec.dns.DnsRecordType
import io.netty.handler.codec.dns.DnsResponseCode
import io.netty.handler.codec.dns.DnsSection
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

import static io.netty.handler.codec.dns.DnsRecord.CLASS_ANY
import static io.netty.handler.codec.dns.DnsRecord.CLASS_CHAOS
import static io.netty.handler.codec.dns.DnsRecord.CLASS_CSNET
import static io.netty.handler.codec.dns.DnsRecord.CLASS_HESIOD
import static io.netty.handler.codec.dns.DnsRecord.CLASS_IN
import static io.netty.handler.codec.dns.DnsRecord.CLASS_NONE
import static io.netty.handler.codec.dns.DnsRecordType.A
import static io.netty.handler.codec.dns.DnsRecordType.AAAA
import static io.netty.handler.codec.dns.DnsRecordType.AXFR
import static io.netty.handler.codec.dns.DnsRecordType.CERT
import static io.netty.handler.codec.dns.DnsRecordType.SPF
import static io.netty.handler.codec.dns.DnsRecordType.TXT
import static io.netty.handler.codec.dns.DnsResponseCode.BADNAME
import static io.netty.handler.codec.dns.DnsResponseCode.NOERROR
import static io.netty.handler.codec.dns.DnsResponseCode.NXDOMAIN
import static io.netty.handler.codec.dns.DnsResponseCode.REFUSED
import static io.netty.handler.codec.dns.DnsSection.ANSWER

@Slf4j
@Unroll
class DnsQueryHandlerSpec extends Specification {
    private static final def counter = new AtomicInteger()
    static def eurekaClient = TestUtils.eurekaClient()

    @Shared
    def config = newConfig(eurekaClient)
    @Shared
    def domain = config.getDomain()

    def clientAddr = new InetSocketAddress(InetAddresses.forString("2a01:260:d001:e744:a1d9:a2b1:c72e:8cfc"), 32456)
    def serverAddr = new InetSocketAddress(InetAddresses.forString("2a01:260:d001:e744::53"), 5353)

    def handler = new DnsQueryHandler(config)
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
        name                                    | expected
        ""                                      | ""
        "  "                                    | ""

        "foo.service.${domain}"                 | ""
        "foo.node.${domain}"                    | ""
        "foo.connect.${domain}"                 | ""
        "_http._tcp.foo.service.${domain}"      | ""
        "_http._tcp.foo.node.${domain}"         | ""
        "_http._tcp.foo.connect.${domain}"      | ""

        "foo.service.${domain}."                | ""
        "foo.node.${domain}."                   | ""
        "foo.connect.${domain}."                | ""
        "_http._tcp.foo.service.${domain}."     | ""
        "_http._tcp.foo.node.${domain}."        | ""
        "_http._tcp.foo.connect.${domain}."     | ""

        "foo.service.dc1.${domain}"             | "dc1"
        "foo.node.dc1.${domain}"                | "dc1"
        "foo.connect.dc1.${domain}"             | "dc1"
        "_http._tcp.foo.service.dc1.${domain}"  | "dc1"
        "_http._tcp.foo.node.dc1.${domain}"     | "dc1"
        "_http._tcp.foo.connect.dc1.${domain}"  | "dc1"

        "foo.service.dc1.${domain}."            | "dc1"
        "foo.node.dc1.${domain}."               | "dc1"
        "foo.connect.dc1.${domain}."            | "dc1"
        "_http._tcp.foo.service.dc1.${domain}." | "dc1"
        "_http._tcp.foo.node.dc1.${domain}."    | "dc1"
        "_http._tcp.foo.connect.dc1.${domain}." | "dc1"

        "foo.service.DC 1.${domain}."           | ""
        "foo.service. DC1.${domain}."           | ""
        "foo.service.DC1 .${domain}."           | ""
        "foo.service. DC1 .${domain}."          | ""
    }

    def "getServiceName(#name) should return #expected"() {
        expect:
        handler.getServiceName(name) == expected

        where:
        name                                    | expected
        ""                                      | ""
        "  "                                    | ""

        "foo.service.${domain}"                 | "foo"
        "foo.node.${domain}"                    | "foo"
        "foo.connect.${domain}"                 | "foo"
        "_http._tcp.foo.service.${domain}"      | "foo"
        "_http._tcp.foo.node.${domain}"         | "foo"
        "_http._tcp.foo.connect.${domain}"      | "foo"

        "foo.service.${domain}."                | "foo"
        "foo.node.${domain}."                   | "foo"
        "foo.connect.${domain}."                | "foo"
        "_http._tcp.foo.service.${domain}."     | "foo"
        "_http._tcp.foo.node.${domain}."        | "foo"
        "_http._tcp.foo.connect.${domain}."     | "foo"

        "foo.service.Dc1.${domain}"             | "foo"
        "foo.node.Dc1.${domain}"                | "foo"
        "foo.connect.Dc1.${domain}"             | "foo"
        "_http._tcp.foo.service.Dc1.${domain}"  | "foo"
        "_http._tcp.foo.node.Dc1.${domain}"     | "foo"
        "_http._tcp.foo.connect.Dc1.${domain}"  | "foo"

        "foo.service.DC1.${domain}."            | "foo"
        "foo.node.Dc1.${domain}."               | "foo"
        "foo.connect.Dc1.${domain}."            | "foo"
        "_http._tcp.foo.service.Dc1.${domain}." | "foo"
        "_http._tcp.foo.node.Dc1.${domain}."    | "foo"
        "_http._tcp.foo.connect.Dc1.${domain}." | "foo"

        "foo.service.DC 1.${domain}."           | ""
        "foo.service. DC1.${domain}."           | ""
        "foo.service.DC1 .${domain}."           | ""
        "foo.service. DC1 .${domain}."          | ""
    }

    def "should respond with REFUSED to any query that is not IN-class"() {
        given:
        def question = createDnsQuestion("foo.${domain}", A, dnsclass)
        def query = createDnsQuery(question)

        when:
        def response = handler.respondToDnsQuery(query)

        then: "handler should refuse to respond to such questions"
        response.code() == DnsResponseCode.REFUSED

        response.count(DnsSection.ADDITIONAL) == 0
        response.recordAt(DnsSection.QUESTION) == question

        response.count(ANSWER) == 0

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
        type | name                     | expectedCode
        // questions that should result in no answers
        //A    | null       | BADNAME
        A    | ""                       | REFUSED
        A    | "  "                     | REFUSED
        A    | "foo"                    | REFUSED
        A    | "foo.bar."               | REFUSED
        A    | "bar"                    | REFUSED
        A    | "bar.service.${domain}"  | NXDOMAIN
        A    | "bar .service.${domain}" | BADNAME

        //AAAA | null       | BADNAME
        AAAA | ""                       | REFUSED
        AAAA | "  "                     | REFUSED
        AAAA | "foo.service.${domain}"  | NXDOMAIN
        AAAA | "foo.bar.${domain}"      | BADNAME
        AAAA | "bar.bar.${domain}."     | BADNAME
    }

    def "should refuse to answer for valid name with unhandled record type: #type"() {
        given:
        def question = createDnsQuestion("foo.${domain}", type)
        def query = createDnsQuery(question)

        when:
        def response = handler.respondToDnsQuery(query)

        then:
        assertResponse(response, question, REFUSED)

        where:
        type << [AXFR, SPF, CERT]
    }

    def "should correctly respond to TXT queries"() {
        given:
        def numAnswers = 4
        def questionName = "corse.service." + config.getDomain() + "."

        def question = createDnsQuestion(questionName, TXT)
        def query = createDnsQuery(question)

        when:
        def response = handler.createResponse(query)

        then:
        assertResponse(response, question, NOERROR, numAnswers)

        when: "extract all answers"
        def answers = (0..(response.count(ANSWER) - 1)).collect { response.recordAt(ANSWER, it) }

        then:
        answers.size() == numAnswers
        answers.each { assert it.name() == questionName }
        answers.each { assert it.dnsClass() == CLASS_IN }
        answers.each { assert it.type() == TXT }
        answers.each { assert it.timeToLive() == config.getTtl() }

        when: "decode txt record payloads"
        def urls = answers.collect {
            ByteBuf buf = it.content()
            buf.readByte()
            buf.toString(StandardCharsets.UTF_8)
        }
        log.info("urls: {}", urls)

        then:
        urls.size() == answers.size()
        urls[0] == 'http://host-100.us-west-2.compute.internal:8080/'
        urls[1] == 'http://host-101.us-west-2.compute.internal/'
        urls[2] == 'https://host-102.us-west-2.compute.internal/'
        urls[3] == 'https://host-104.us-west-2.compute.internal:8443/'
    }

    def assertResponse(DatagramDnsResponse response,
                       DnsQuestion question,
                       DnsResponseCode expectedCode = NOERROR,
                       int numAnswers = 1) {
        assert response.code() == expectedCode
        assert response.recipient() == clientAddr
        assert response.sender() == serverAddr

        assert response.recordAt(DnsSection.QUESTION) == question

        if (expectedCode == NOERROR) {
            assert response.count(ANSWER) == numAnswers
        }

        true
    }

    DnsServerConfig newConfig(EurekaClient client = eurekaClient) {
        TestUtils.defaultConfig(client)
    }

    DnsQuestion createDnsQuestion(
            String name,
            DnsRecordType type = A,
            int dnsclass = CLASS_IN) {
        new DefaultDnsQuestion(name, type, dnsclass)
    }

    DatagramDnsQuery createDnsQuery(DnsQuestion question) {
        query.addRecord(DnsSection.QUESTION, question)
        query
    }
}
