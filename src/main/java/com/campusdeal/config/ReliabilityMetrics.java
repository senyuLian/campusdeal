package com.campusdeal.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Bounded-label counters shared by cache, retrieval, Agent and admission paths. */
@Component
public class ReliabilityMetrics {

    private final MeterRegistry registry;

    @Autowired
    public ReliabilityMetrics(@Autowired(required = false) MeterRegistry registry) {
        this.registry = registry;
    }

    public void increment(String name, String outcome) {
        if (registry == null) return;
        Counter.builder(name)
                .tag("outcome", bounded(outcome))
                .register(registry)
                .increment();
    }

    /**
     * Read a bounded counter for health probes and operator diagnostics.
     * Returning zero when metrics are not configured keeps local/unit startup
     * independent from the Actuator registry.
     */
    public double count(String name, String outcome) {
        if (registry == null) return 0.0;
        Counter counter = registry.find(name)
                .tag("outcome", bounded(outcome))
                .counter();
        return counter == null ? 0.0 : counter.count();
    }

    private String bounded(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.length() > 32 ? value.substring(0, 32) : value;
    }
}
