package com.github.bfg.eureka.dns.spring

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification

@SpringBootTest
@ActiveProfiles("test")
abstract class SpringSpec extends Specification {
}