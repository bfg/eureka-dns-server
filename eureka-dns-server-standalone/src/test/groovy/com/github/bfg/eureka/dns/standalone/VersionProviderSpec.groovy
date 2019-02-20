package com.github.bfg.eureka.dns.standalone

import spock.lang.Specification

class VersionProviderSpec extends Specification {
    def "should read correct version"() {
        when:
        def result = new VersionProvider().getVersion()

        then:
        result.size() == 1
        result[0] == '0.9.9'
    }
}
