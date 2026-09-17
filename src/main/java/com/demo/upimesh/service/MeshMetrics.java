package com.demo.upimesh.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class MeshMetrics {

    private final MeterRegistry registry;

    public MeshMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void ingest(IngestResult.IngestOutcome outcome) {
        registry.counter("upi.ingest", "outcome", outcome.name()).increment();
    }

    public void settlement(String status) {
        registry.counter("upi.settlement", "status", status).increment();
    }
}
