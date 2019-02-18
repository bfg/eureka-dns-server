package com.github.bfg.eureka.dns.spring;

import com.github.bfg.eureka.dns.DnsServerConfig;
import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Enables Eureka DNS server by auto-importing {@link EurekaDnsServerConfiguration}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
@Import({EurekaDnsServerConfiguration.class, DnsServerConfig.class})
public @interface EnableEurekaDnsServer {
}
