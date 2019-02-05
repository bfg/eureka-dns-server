package com.github.bfg.eureka.dns.standalone

import groovy.util.logging.Slf4j
import spock.lang.Specification

@Slf4j
class EurekaDnsServerCliSpec extends Specification {
    def "foo test"() {
        given:
        log.info("this is dummy test.")

        expect:
        1 == 1
    }
}
