package com.github.bfg.eureka.dns.spring

import com.github.bfg.eureka.dns.ConfigHolder
import com.github.bfg.eureka.dns.DnsIntegrationSpec
import com.github.bfg.eureka.dns.DnsServerConfig
import groovy.util.logging.Slf4j
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.yaml.snakeyaml.Yaml

@Slf4j
@SpringBootTest(classes = SpringApp)
@ActiveProfiles("test")
class SpringAppITSpec extends DnsIntegrationSpec {
    def setupSpec() {
        ConfigHolder.set(readSpringDnsServerConfig())
    }

    DnsServerConfig readSpringDnsServerConfig() {
        def input = getClass().getResourceAsStream("/application.yml")
        def yaml = new Yaml()
        def iterator = yaml.loadAll(input)
        def cfg = iterator.find { it?.eureka?.dns?.server }
        def map = cfg.eureka.dns.server

        def config = new DnsServerConfig()
                .setPort(map.port)
                .setTtl(map.ttl)
                .setMaxResponses(map."max-responses")
                .setMaxThreads(map."max-threads")
                .setPreferNativeTransport(map."prefer-native-transport")
                .setDomain(map.domain)
                .setLogQueries(map."log-queries")
        log.info("read spring dns server config: {}", config)
        config
    }

    @Override
    DnsServerConfig getConfig() {
        ConfigHolder.get()
    }
}