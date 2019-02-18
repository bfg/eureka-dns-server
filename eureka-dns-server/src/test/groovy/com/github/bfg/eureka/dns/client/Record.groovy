package com.github.bfg.eureka.dns.client

import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString

@ToString(includePackage = false, includeNames = true)
@EqualsAndHashCode
abstract class Record {
    String name
    int ttl
    String dnsClass
    String type
}

@ToString(includePackage = false, includeNames = true, includeSuperProperties = true)
@EqualsAndHashCode(callSuper = true)
class GenericRecord extends Record {
    String answer
}

@ToString(includePackage = false, includeNames = true, includeSuperProperties = true)
@EqualsAndHashCode(callSuper = true)
class SOARecord extends Record {
    String nameserver
    String mbox
    int serial
    int refresh
    int retry
    int expire
    int minTtl
}

@ToString(includePackage = false, includeNames = true, includeSuperProperties = true)
@EqualsAndHashCode(callSuper = true)
class SRVRecord extends Record {
    int priority
    int weight
    int port
    String target
}