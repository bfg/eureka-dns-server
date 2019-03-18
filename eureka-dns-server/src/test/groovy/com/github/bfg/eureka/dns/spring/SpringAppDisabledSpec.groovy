package com.github.bfg.eureka.dns.spring

import com.github.bfg.eureka.dns.DnsServerConfig
import com.github.bfg.eureka.dns.EurekaDnsServer
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles

@Slf4j
@ActiveProfiles(profiles = "default", inheritProfiles = false)
class SpringAppDisabledSpec extends SpringSpec {
    @Autowired
    ApplicationContext appCtx

    @Autowired
    DnsServerConfig config

    def "should wire dependencies"() {
        expect:
        appCtx != null
        config != null
    }

    def "server bean should not be initialized"() {
        when:
        def server = appCtx.getBean(EurekaDnsServer)

        then:
        thrown(NoSuchBeanDefinitionException)
        server == null
    }
}
