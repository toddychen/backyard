package com.backyard.playground.observation;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;

import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;

/**
 * Excludes noisy internal paths from tracing. Filtering at the Micrometer
 * Observation layer means no span is created at all — cleaner than
 * sampler-based approaches which still create the span before discarding it.
 */
@Component
public class ActuatorObservationFilter implements ObservationPredicate {

    @Override
    public boolean test(String name, Observation.Context context) {
        if (context instanceof ServerRequestObservationContext ctx) {
            return !ctx.getCarrier().getRequestURI().startsWith("/actuator");
        }
        return true;
    }
}
