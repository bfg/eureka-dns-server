package com.github.bfg.eureka.dns;

import lombok.RequiredArgsConstructor;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * Element counter used in streams, because java 8 streams don't have take(int) implemented.
 */
@RequiredArgsConstructor
final class RecordCounter implements Predicate {
    private final AtomicInteger counter = new AtomicInteger();

    private final int limit;

    @Override
    public boolean test(Object t) {
        if (limit < 1) {
            return true;
        }
        return counter.incrementAndGet() <= limit;
    }
}
