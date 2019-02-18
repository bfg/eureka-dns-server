package com.github.bfg.eureka.dns.client

import groovy.transform.ToString

@ToString(includePackage = false)
class DnsResponse {
    String status = 'UNKNOWN'
    String error = ''
    List<Record> answers = []
    List<Record> authorities = []
    List<Record> additionals = []
}
