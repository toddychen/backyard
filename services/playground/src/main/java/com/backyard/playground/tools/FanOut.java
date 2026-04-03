package com.backyard.playground.tools;

import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

/**
 * Executes a mapping function over a list in parallel using virtual threads,
 * wrapping each item's work in a trace span.
 *
 * <p>
 * Usage:
 *
 * <pre>
 * List&lt;Game&gt; games = fanOut.map(
 *         rawGames,
 *         g -&gt; gameService.getGameDetails(g.gameId()),
 *         "game.details", // appears as "virtual-thread:game.details" in Jaeger
 *         g -&gt; g.gameId());
 * </pre>
 *
 * <p>
 * Each item runs on its own virtual thread. The span name and item key appear
 * in Jaeger, with any child spans (cache, outbound calls) nested underneath.
 */
@Component
public class FanOut {

    private static final Logger log = LoggerFactory.getLogger(FanOut.class);

    // Global registry that observations report into. When a new observation is
    // created inside a virtual thread, it reads the active span from the thread
    // context (restored by contextSnapshotFactory) and nests under it.
    private final ObservationRegistry observationRegistry;

    // Captures the parent thread's context (trace ID, active span, MDC) at
    // submit time and restores it on each virtual thread before it runs —
    // without this, virtual threads start with blank context and spans appear
    // as disconnected root traces in Jaeger instead of children of the request.
    private final ContextSnapshotFactory contextSnapshotFactory = ContextSnapshotFactory.builder().build();

    public FanOut(ObservationRegistry observationRegistry) {
        this.observationRegistry = observationRegistry;
    }

    private static final String SPAN_PREFIX = "virtual-thread:";

    /** Without per-item key tag — use when there is no meaningful single ID. */
    public <T, R> List<R> map(List<T> items, Function<T, R> task, String spanName) {
        return map(items, task, spanName, null);
    }

    /**
     * Maps each item in parallel. Null results are filtered out (e.g. item not
     * found). Exceptions per item are logged as warnings and skipped.
     *
     * @param items    input list
     * @param task     function to apply to each item (may do I/O)
     * @param spanName span name suffix — prefixed with {@code "virtual-thread:"} so
     *                 spans appear as e.g. {@code "virtual-thread:game.details"} in
     *                 Jaeger
     * @param spanKey  extracts a value tagged as {@code item.id} on the span, or
     *                 {@code null} to omit the tag
     */
    public <T, R> List<R> map(
            List<T> items, Function<T, R> task, String spanName, Function<T, String> spanKey) {
        try (ExecutorService executor = ContextExecutorService.wrap(
                Executors.newVirtualThreadPerTaskExecutor(),
                contextSnapshotFactory::captureAll)) {
            List<Future<R>> futures = items.stream()
                    .map(
                            item -> executor.submit(
                                    () -> {
                                        Observation obs = Observation.createNotStarted(
                                                SPAN_PREFIX + spanName,
                                                observationRegistry);
                                        if (spanKey != null)
                                            obs.lowCardinalityKeyValue(
                                                    "item.id", spanKey.apply(item));
                                        obs.start();
                                        try (var scope = obs.openScope()) {
                                            return task.apply(item);
                                        } finally {
                                            obs.stop();
                                        }
                                    }))
                    .toList();
            return futures.stream()
                    .map(
                            f -> {
                                try {
                                    return f.get();
                                } catch (Exception e) {
                                    log.warn("fan-out task failed", e);
                                    return null;
                                }
                            })
                    .filter(Objects::nonNull)
                    .toList();
        }
    }
}
