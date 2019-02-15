package com.github.bfg.eureka.dns.spring;

import com.github.bfg.eureka.dns.DnsServerConfig;
import com.github.bfg.eureka.dns.EurekaDnsServer;
import com.netflix.discovery.EurekaClient;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

/**
 * Eureka DNS server spring auto configuration.
 */
@Slf4j
@Configuration
public class EurekaDnsServerConfiguration {
    /**
     * Creates eureka dns server bean.
     *
     * @param config       dns server config
     * @param eurekaClient eureka client
     * @return dns server instance.
     */
    @Bean
    @SneakyThrows
    @ConditionalOnMissingBean
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    public EurekaDnsServer eurekaDnsServer(@NonNull DnsServerConfig config, @NonNull EurekaClient eurekaClient) {
        val server = config.clone()
                .setEurekaClient(eurekaClient)
                .create();

        log.debug("starting created eureka dns server: {}", server);
        return server.start().toCompletableFuture().get();
    }
}
