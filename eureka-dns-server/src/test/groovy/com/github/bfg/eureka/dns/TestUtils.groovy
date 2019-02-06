package com.github.bfg.eureka.dns

import com.netflix.discovery.EurekaClient

class TestUtils {
    static EurekaClient eurekaClient = FakeEurekaClient.defaults()

    static EurekaClient eurekaClient() {
        eurekaClient
    }

    static DnsServerConfig defaultConfig(EurekaClient client = eurekaClient) {
        new DnsServerConfig().setEurekaClient(client)
                             .setMaxResponses(4)
                             .setPort(new ServerSocket(0).getLocalPort())
                             .setLogQueries(true)
    }
}
