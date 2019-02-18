package com.github.bfg.eureka.dns

import com.netflix.discovery.EurekaClient
import groovy.util.logging.Slf4j
import spock.lang.Shared

@Slf4j
class EurekaDnsServerITSpec extends DnsIntegrationSpec {
    static final EurekaClient eurekaClient = FakeEurekaClient.defaults()

    @Shared
    EurekaDnsServer server

    def setup() {
        if (!server) {
            def cfg = getConfig().clone()
                                 .setEurekaClient(eurekaClient)
            server = new EurekaDnsServer(cfg)
            server.start()
        }
    }

    def cleanupSpec() {
        server?.close()
    }
}
