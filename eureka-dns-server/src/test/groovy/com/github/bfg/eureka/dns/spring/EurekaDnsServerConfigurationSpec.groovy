package com.github.bfg.eureka.dns.spring

import com.github.bfg.eureka.dns.DnsServerConfig
import com.netflix.discovery.EurekaClient
import groovy.util.logging.Slf4j
import io.netty.channel.nio.NioEventLoopGroup
import spock.lang.Specification
import spock.lang.Unroll

@Slf4j
@Unroll
class EurekaDnsServerConfigurationSpec extends Specification {
    def instance = new EurekaDnsServerConfiguration()

    def "eurekaDnsServer() should throw on null arguments"() {
        if (config && eurekaClient) return

        when:
        def server = instance.eurekaDnsServer(config, eurekaClient)

        then:
        thrown(NullPointerException)
        server == null

        where:
        [config, eurekaClient] << [[new DnsServerConfig(), null], [Mock(EurekaClient), null]].combinations()
    }

    def "should create started dns instance using native event loop group: #preferNativeTransport"() {
        given: "setup eureka client"
        def eurekaClientA = Mock(EurekaClient)
        def eurekaClientB = Mock(EurekaClient)

        and: "setup eureka config"
        def config = new DnsServerConfig()
                .setMaxResponses(2)
                .setPort(8553)
                .setMaxThreads(3)
                .setEurekaClient(eurekaClientA)
                .setPreferNativeTransport(preferNativeTransport)

        when: "create server with second eureka client"
        def server = instance.eurekaDnsServer(config, eurekaClientB)

        then:
        def rtConfig = server.config

        !rtConfig.is(config) // server should have it's own config instance
        rtConfig.getEurekaClient().is(eurekaClientB) // should contain correct eureka client

        // runtime config should be equal to original config when eureka client is removed
        rtConfig.clone().setEurekaClient(null) == config.clone().setEurekaClient(null)

        // server should be running
        server.isRunning()

        // event loop should have correct number of workers
        server.eventLoopGroup.executorCount() == config.getMaxThreads()

        // test event loop group type
        if (preferNativeTransport) {
            def elgClass = server.eventLoopGroup.getClass().getName()
            assert elgClass == "io.netty.channel.epoll.EpollEventLoopGroup" || elgClass == "io.netty.channel.kqueue.KQueueEventLoopGroup"
        } else {
            assert server.eventLoopGroup instanceof NioEventLoopGroup
        }

        cleanup:
        if (server?.isRunning()) {
            server.close()
        }

        where:
        preferNativeTransport << [true, false]
    }
}
