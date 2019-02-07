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

    def "should correctly parse data from various files"() {
        when:
        def client = new FakeEurekaClient().defaults()

        then:
        client.getApplications().size() == 7
    }
}
