package com.github.bfg.eureka.dns

import com.netflix.discovery.EurekaClient
import io.netty.channel.EventLoopGroup
import spock.lang.Specification
import spock.lang.Unroll

@Unroll
class DnsServerConfigSpec extends Specification {
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

    def "normal instance should be fine"() {
        given:
        def eurekaClient = Mock(EurekaClient)
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
}
