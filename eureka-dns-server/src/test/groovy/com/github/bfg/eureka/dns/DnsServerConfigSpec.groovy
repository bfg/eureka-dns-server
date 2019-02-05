package com.github.bfg.eureka.dns

import com.netflix.discovery.EurekaClient
import io.netty.channel.EventLoopGroup
import spock.lang.Specification
import spock.lang.Unroll

@Unroll
class DnsServerConfigSpec extends Specification {
    def eurekaClient = Mock(EurekaClient)

    def "creator should throw in case of missing props"() {
        when:
        def config = new DnsServerConfig().validate()

        then:
        thrown(RuntimeException)
        config == null
    }

    def "validate() should throw in case of bad props"() {
        given:
        def builder = new DnsServerConfig().setEurekaClient(Mock(EurekaClient))

        when: "configure builder, create instance"
        configurer.call(builder)
        def config = builder.validate()

        then:
        thrown(RuntimeException)
        config == null

        where:
        configurer << [
                { it.eurekaClient(null) },
                { def elg = Mock(EventLoopGroup); elg.isShutdown() >> true; it.setEventLoopGroup(elg) },
                { it.setAddress(null) }, // bad listening address
                { it.setPort(-1) },
                { it.setPort(0) },
                { it.setPort(65536) },
                { it.setPort(100_000) }
        ]
    }

    def "normal instance should contain sensible defaults"() {
        given:
        def config = new DnsServerConfig().setEurekaClient(eurekaClient)

        expect:
        config.getPort() == 5353
        config.getEurekaClient().is(eurekaClient)
        config.getAddresses().isEmpty()
        config.getEventLoopGroup() == null

        config.getTtl() == 5
        config.getMaxResponses() == 5
        config.getDomain() == "eureka"
    }

    def "validation of default instance should complain about missing eureka client"() {
        when:
        def config = new DnsServerConfig().validate()

        then:
        def exception = thrown(IllegalStateException)
        exception.getMessage().contains("Eureka client")

        config == null
    }

    def "cloning/validating an instance should result in equal, but not the same instance"() {
        given:
        def elg = Mock(EventLoopGroup)
        def addresses = ["127.0.0.1", "::1"]
        def domain = "registry"
        def config = new DnsServerConfig()
                .setPort(1010)
                .setLogQueries(true)
                .setAddresses(addresses)
                .setEventLoopGroup(elg)
                .setEurekaClient(eurekaClient)
                .setTtl(42)
                .setMaxResponses(2)
                .setMaxThreads(31)
                .setPreferNativeTransport(false)
                .setDomain(domain)
                .setLogQueries(true)

        when: "clone config"
        def cloned = config.clone()

        then:
        cloned == config
        !cloned.is(config)

        cloned.getEventLoopGroup().is(elg)
        cloned.getEurekaClient().is(eurekaClient)
        cloned.getAddresses() == addresses as Set

        when: "validate config"
        def validated = config.validate()

        then:
        validated == config
        !validated.is(config)
    }
}
