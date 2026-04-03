package com.backyard.playground.service;

import io.micrometer.tracing.Tracer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TraceService {
    private static final Logger log = LoggerFactory.getLogger(TraceService.class);

    private final Tracer tracer;

    public TraceService(Tracer tracer) {
        this.tracer = tracer;
    }

    public record TraceInfo(String traceId, String spanId) {
    }

    public TraceInfo currentTrace() {
        var span = tracer.currentSpan();
        String traceId = span != null ? span.context().traceId() : "n/a";
        String spanId = span != null ? span.context().spanId() : "n/a";
        log.info("tracing check: traceId='{}' spanId='{}'", traceId, spanId);
        return new TraceInfo(traceId, spanId);
    }
}
