package com.github.bfg.eureka.dns.spring

import com.github.bfg.eureka.dns.DnsServerConfig
import com.github.bfg.eureka.dns.EurekaDnsServer
import groovy.util.logging.Slf4j
import io.netty.channel.nio.NioEventLoopGroup
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext

@Slf4j
class SpringAppSpec extends SpringSpec {
    @Autowired
    ApplicationContext appCtx

    @Autowired
    EurekaDnsServer server

    @Autowired
    DnsServerConfig config

    def "should wire dependencies"() {
        expect:
        appCtx != null
        server != null
        config != null
    }

    def "wired config should contain correct values"() {
        log.info("assigned config: {}", config)

        expect:
        config.getPort() == 9553
        config.getAddresses() == ['127.0.0.1', '::1'] as Set
        config.getTtl() == 9
        config.getMaxResponses() == 4
        config.getMaxThreads() == 7
        !config.isPreferNativeTransport()
        config.getDomain() == 'my-eureka'
        config.isLogQueries()
    }

    def "server should be running and should be correctly configured"() {
        expect:
        server.isRunning()

        // server should contain it's own config instance
        !server.config.is(config)

        // server should contain equal copy of configuration without eureka client
        def serverConfigWithoutEureka = server.config.clone().setEurekaClient(null)
        serverConfigWithoutEureka == config

        // check event loop group
        server.eventLoopGroup instanceof NioEventLoopGroup
        server.eventLoopGroup.executorCount() == config.getMaxThreads()
    }

    def "application context should return singleton dns server instance by class"() {
        when:
        def servers = (1..10).collect { appCtx.getBean(EurekaDnsServer) }
        def first = servers.first()

        then:
        first != null
        servers.each { assert it.is(first) }
    }

    def "application context should return singleton dns server instance by name"() {
        when:
        def servers = (1..10).collect { appCtx.getBean("eurekaDnsServer") }
        def first = servers.first()

        then:
        first != null
        servers.each { assert it.is(first) }
    }
}
