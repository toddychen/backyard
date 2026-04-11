package com.backyard.playground.server.rest.controller;

import com.backyard.playground.context.ClientLocaleContextHolder;
import com.backyard.playground.service.EchoService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.context.MessageSource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.Executors;
import java.util.random.RandomGenerator;

@Tag(name = "Echo", description = "Echo endpoint for testing")
@RestController
@RequestMapping("/{version}/echo")
public class EchoController extends BaseController {

    private final EchoService echoService;
    private final MessageSource messageSource;

    public EchoController(EchoService echoService, MessageSource messageSource) {
        this.echoService = echoService;
        this.messageSource = messageSource;
    }

    public record HelloResult(String locale, String text) {
    }

    @Operation(summary = "Echo a message back with metadata")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = EchoService.EchoResult.class)))
    @GetMapping(version = "1+")
    public ResponseEntity<Object> echo(
            @RequestParam("message") String message,
            @RequestParam(value = "from", required = false) String from) {
        return ok(echoService.echo(message, from));
    }

    /**
     * Returns the word "Hello" translated into the locale resolved from the
     * request's {@code
     * Accept-Language} header. Demonstrates the full locale pipeline: header →
     * LocaleFilter → ClientLocaleContext → MessageSource lookup.
     */
    @Operation(summary = "Return 'Hello' in the request locale")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = HelloResult.class)))
    @GetMapping(value = "/hello", version = "1+")
    public ResponseEntity<Object> hello() {
        var ctx = ClientLocaleContextHolder.get();
        var locale = ctx.supportedLocale().toLocale();
        var label = messageSource.getMessage("hello", null, locale);
        return ok(new HelloResult(ctx.supportedLanguageTag(), label));
    }

    /**
     * Streams tokens one by one with random delays via SSE, simulating LLM token
     * streaming. Each event carries a single token string. The stream completes
     * after all tokens are sent. This is a Spring MVC example — see threading
     * notes below for trade-offs vs. WebFlux.
     *
     * <p>
     * <b>Threading model:</b> SSE holds an HTTP connection open for the duration of
     * the stream. Traditionally this was a concern with platform threads — each
     * open SSE connection tied up a thread from Tomcat's request thread pool
     * (default 200), limiting concurrent streams to ~200 before new connections
     * were rejected.
     *
     * <p>
     * With virtual threads ({@code spring.threads.virtual.enabled=true}), Tomcat
     * dispatches each inbound request onto a virtual thread instead of a platform
     * thread. Virtual threads are parked (not pinned to a carrier thread) during
     * blocking I/O and {@code Thread.sleep()}, so holding a connection open costs
     * almost no OS resources. The event-emitting loop here also runs on a dedicated
     * virtual thread (via {@code newVirtualThreadPerTaskExecutor}), so the delay
     * between tokens does not block anything. Together, thousands of concurrent
     * SSE streams become practical without any thread pool tuning.
     *
     * <p>
     * <b>WebFlux alternative:</b> Spring WebFlux with {@code Flux<ServerSentEvent>}
     * is the idiomatic reactive approach and achieves similar throughput via
     * non-blocking I/O. However, mixing WebFlux and Spring MVC in the same JVM is
     * not supported — one displaces the other. A service that is already on Spring
     * MVC (like this one) should use {@code SseEmitter} + virtual threads rather
     * than migrating to WebFlux just for SSE. A dedicated WebFlux service is a
     * valid option if SSE streaming is the primary concern.
     *
     * <p>
     * Try with: {@code curl -N http://localhost:2080/api/v1/echo/stream?count=5}
     */
    @Operation(summary = "Stream tokens one by one via SSE (simulates LLM streaming)")
    @GetMapping(value = "/stream", version = "1+", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(value = "count", defaultValue = "5") int count) {
        SseEmitter emitter = new SseEmitter();
        Executors.newVirtualThreadPerTaskExecutor().execute(() -> {
            RandomGenerator random = RandomGenerator.getDefault();
            try {
                for (int i = 0; i < count; i++) {
                    emitter.send(SseEmitter.event().data("token " + i));
                    Thread.sleep(200 + random.nextLong(600));
                }
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }
}
