package com.github.bfg.eureka.dns

import com.github.bfg.eureka.dns.client.DnsClient
import groovy.util.logging.Slf4j
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

@Slf4j
@Unroll
abstract class DnsIntegrationSpec extends Specification {
    @Shared
    DnsClient client

    DnsServerConfig getConfig() {
        TestUtils.defaultConfig()
    }

    String getDomain() {
        getConfig().getDomain()
    }

    def setup() {
        if (!client) {
            client = new DnsClient("127.0.0.1", getConfig().getPort())
        }
    }

    def lookup(String name, String type = "A", String dnsClass = "IN") {
        def res = client.resolve(name, type, dnsClass)
        [res, res.answers, res.authorities, res.additionals]
    }

    def "should correctly respond to NS query: #name"() {
        when:
        def (res, answers, authorities, additionals) = lookup(name, "NS")

        then:
        res.status == "NOERROR"
        answers.size() == 1
        authorities.size() == 0
        additionals.size() == 1

        // inspect answer record
        def record = answers.first()
        record.dnsClass == "IN"
        record.type == "NS"
        record.ttl == config.getTtl()
        record.answer == "ns." + config.getDomain()

        // inspect additionals
        def additional = additionals.first()
        additional.dnsClass == "IN"
        additional.type == "A"
        additional.ttl == config.getTtl()
        additional.answer == client.getAddress()

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
        def currentTs = System.currentTimeMillis() / 1000
        def expectedSerial = (int) currentTs

        when:
        def (res, answers, authorities, additionals) = lookup(name, "SOA")

        then:
        res.status == "NOERROR"

        answers.size() == 1
        authorities.size() == 1
        additionals.size() == 1

        then:

        // inspect answer record
        answers[0].dnsClass == "IN"
        answers[0].type == "SOA"
        answers[0].ttl == config.getTtl()

        answers[0].nameserver == "ns." + getDomain()
        answers[0].mbox == "hostmaster." + getDomain()
        answers[0].serial == expectedSerial || answers[0].serial == (expectedSerial + 1)
        answers[0].refresh == 3600
        answers[0].retry == 600
        answers[0].expire == 86400
        answers[0].minTtl == 0

        authorities[0].dnsClass == "IN"
        authorities[0].type == "NS"
        authorities[0].ttl == config.getTtl()
        authorities[0].name == name || authorities[0].name == "ns." + getDomain()
        authorities[0].answer == "ns." + getDomain()

        additionals[0].dnsClass == "IN"
        additionals[0].type == "A"
        additionals[0].ttl == config.getTtl()
        authorities[0].name == name || authorities[0].name == "ns." + getDomain()
        additionals[0].answer == client.getAddress()

        where:
        name << [
                config.getDomain(),
                "service." + config.getDomain(),
                "corse.service." + config.getDomain(),
                "_corse._tcp.service." + config.getDomain(),
        ]
    }

    def "should correctly respond to TXT query in default region: #name"() {
        when:
        def (res, answers, authorities, additionals) = lookup(name, "TXT")

        then:
        res.status == "NOERROR"

        answers.size() == getConfig().getMaxResponses()
        authorities.size() == 0
        additionals.size() == 0

        answers.each { assert it.dnsClass == "IN" }
        answers.each { assert it.type == "TXT" }
        answers.each { assert it.ttl == config.getTtl() }
        answers.each { assert it.name == name }

        when: "weed out urls"
        def urls = answers.collect { it.answer }

        then:
        urls[0] == 'http://host-100.us-west-2.compute.internal:8080/'
        urls[1] == 'http://host-101.us-west-2.compute.internal/'
        urls[2] == 'https://host-102.us-west-2.compute.internal/'
        urls[3] == 'https://host-104.us-west-2.compute.internal:8443/'

        where:
        name << [
                "corse.service.${domain}",
                "corse.service.default.${domain}",
        ]
    }

    def "should correctly respond to TXT query in non-default region: #name"() {
        when:
        def (res, answers, authorities, additionals) = lookup(name, "TXT")

        then:
        res.status == "NOERROR"

        answers.size() == 1
        authorities.size() == 0
        additionals.size() == 0

        answers[0].dnsClass == "IN"
        answers[0].type == "TXT"
        answers[0].ttl == config.getTtl()
        answers[0].name == name
        answers[0].answer == "http://host-152.us-west-2.compute.internal:8080/"

        where:
        name << [
                "mallorca.service.dc1.${domain}",
        ]
    }

    def "should return empty set of TXT records for unknown service"() {
        when:
        def (res, answers, authorities, additionals) = lookup("foo.service.${domain}", "TXT")

        then:
        res.status == "NXDOMAIN"
        answers.isEmpty()
        authorities.isEmpty()
        additionals.isEmpty()
    }

    //
    // BEGIN: RFC2782
    //

    def "should correctly respond SRV records for: #name"() {
        when:
        def (res, answers, authorities, additionals) = lookup(name, "SRV")

        then:
        res.status == "NOERROR"

        answers.size() == config.getMaxResponses()
        authorities.size() == 0
        additionals.size() == config.getMaxResponses()

        answers.each { assert it.name.startsWith(name) }
        answers.each { assert it.ttl == config.getTtl() }
        answers.each { assert it.dnsClass == "IN" }
        answers.each { assert it.type == "SRV" }
        answers.each { assert it.priority == 1 }
        answers.each { assert it.weight == 10 }
        answers.each { assert it.port > 0 }
        answers.each { assert !it.target.isEmpty() }

        additionals.each { assert it.name.matches('^host-\\d+.us-west-2.compute.internal$') }
        additionals.each { assert it.ttl == config.getTtl() }
        additionals.each { assert it.dnsClass == "IN" }
        additionals.each { assert it.type == "A" || it.type == "AAAA" }
        additionals.each { assert it.answer.startsWith('10.11.') || it.answer == '::2' }

        where:
        name << [
                "corse.service.${domain}",
                "corse.service.default.${domain}",
                "_corse._tcp.service.default.${domain}",
        ]
    }

    def "should not return SRV records for instances that are registered as ip addresses for: #name"() {
        when:
        def (res, answers, authorities, additionals) = lookup(name, "SRV")

        then:
        res.status == "NXDOMAIN"

        answers.isEmpty()
        authorities.isEmpty()
        additionals.isEmpty()

        where:
        name << [
                "sicily.service.dc1.${domain}",
                "_sicily._tcp.service.dc1.${domain}",
        ]
    }

    //
    // END:   RFC2782
    //
}
