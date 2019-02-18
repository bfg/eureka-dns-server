package com.github.bfg.eureka.dns

import groovy.util.logging.Slf4j

/**
 * Dns server config holder, used for spring-app tests
 */
@Slf4j
class ConfigHolder {
    private static DnsServerConfig cfg

    static DnsServerConfig get() {
        cfg
    }

    static set(DnsServerConfig config) {
        cfg = config
        log.info("set config: {}", config)
    }
}
