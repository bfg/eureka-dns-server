package com.github.bfg.eureka.dns

import groovy.util.logging.Slf4j
import spock.lang.Specification
import spock.lang.Unroll

@Slf4j
@Unroll
class FakeEurekaClientSpec extends Specification {
    def "new instance should be empty"() {
        given:
        def client = new FakeEurekaClient()

        expect:
        client.getApplications() != null
        client.getApplication("foo") == null
    }

    def "should read data from file"() {
        when:
        def client = new FakeEurekaClient().defaults()
        log.info("created: {}", client)

        then:
        client.getApplications().size() > 10
    }
}
