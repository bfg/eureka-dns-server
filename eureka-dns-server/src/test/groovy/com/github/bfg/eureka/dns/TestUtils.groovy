package com.github.bfg.eureka.dns

import com.netflix.discovery.EurekaClient

class TestUtils {
    static EurekaClient eurekaClient = FakeEurekaClient.defaults()

    static EurekaClient eurekaClient() {
        eurekaClient
    }

    static DnsServerConfig defaultConfig(EurekaClient client = eurekaClient) {
        new DnsServerConfig().setEurekaClient(client)
                             .setPort(5454)
                             .setMaxResponses(4)
                             .setDomain("meureka")
                             .setLogQueries(true)
    }
}
