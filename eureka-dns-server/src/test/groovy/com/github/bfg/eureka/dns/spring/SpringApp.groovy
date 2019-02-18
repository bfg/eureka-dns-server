package com.github.bfg.eureka.dns.spring

import com.github.bfg.eureka.dns.ConfigHolder
import com.github.bfg.eureka.dns.DnsServerConfig
import com.github.bfg.eureka.dns.EurekaDnsServer
import com.github.bfg.eureka.dns.FakeEurekaClient
import com.netflix.discovery.EurekaClient
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Scope
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

import javax.annotation.PostConstruct

@Slf4j
@RestController
@SpringBootApplication
@EnableEurekaDnsServer
class SpringApp {
    @Autowired
    DnsServerConfig config

    void main(String... args) {
        log.info("running spring app")
        SpringApplication.run(SpringApp, args)
    }

    @PostConstruct
    void init() {
        log.info("app initialized with dns server config: {}", config)
        ConfigHolder.set(config)
    }

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    EurekaClient eurekaClient() {
        FakeEurekaClient.defaults()
    }

    @Bean
    String fooString(EurekaDnsServer server) {
        ConfigHolder.set(server.config)
        "fooString"
    }

    @GetMapping("/")
    String foo() {
        "foo"
    }
}
