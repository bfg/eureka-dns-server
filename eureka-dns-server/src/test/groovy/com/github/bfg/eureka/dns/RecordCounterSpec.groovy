package com.github.bfg.eureka.dns

import spock.lang.Specification
import spock.lang.Unroll

import java.util.stream.Collectors

@Unroll
class RecordCounterSpec extends Specification {
    def elements = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]
    def stream = elements.stream()

    def "should be no-op if limit is < 1"() {
        given:
        def predicate = new RecordCounter(limit)

        when:
        def result = stream.filter(predicate).collect(Collectors.toList())

        then:
        result == elements

        where:
        limit << [0, -1, -2]
    }

    def "should limit elements in stream"() {
        given:
        def predicate = new RecordCounter(limit)

        when:
        def result = stream.filter(predicate).collect(Collectors.toList())

        then:
        result.size() == limit
        result == elements.subList(0, limit)

        where:
        limit << [1, 3, 5]
    }
}
