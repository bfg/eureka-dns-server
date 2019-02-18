package com.github.bfg.eureka.dns.client

import groovy.util.logging.Slf4j
import spock.lang.Specification

@Slf4j
class DnsClientSpec extends Specification {
    def "should correctly resolve simple dns name"() {
        given:
        def query = "www.google.com"
        def client = new DnsClient("8.8.8.8", 53)

        when:
        def res = client.resolve(query)
        log.info("resolved: {}", res)
        log.info("answers: {}", res.answers.size())

        then:
        res.status == "NOERROR"

        def answers = res.answers
        answers.size() >= 1

        answers.each { assert it.ttl > 0 }
        answers.each { assert it.dnsClass == "IN" }
        answers.each { assert it.type == "A" }
        answers.each { assert it.name == query }
        answers.each { assert it.answer.matches('^\\d+\\.\\d+\\.\\d+\\.\\d+$') }
    }
}
