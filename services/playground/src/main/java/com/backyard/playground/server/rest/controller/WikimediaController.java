package com.backyard.playground.server.rest.controller;

import com.backyard.playground.service.WikimediaService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import reactor.core.Disposable;

import java.io.IOException;

/**
 * Proxies the Wikimedia EventStreams recent-change feed as SSE.
 *
 * <p>
 * Demonstrates WebClient SSE consumption in a Spring MVC service: a reactive
 * {@link reactor.core.publisher.Flux} from {@link WikimediaService} is bridged
 * to a servlet-world {@link SseEmitter}. The upstream subscription is disposed
 * whenever the emitter ends (completion, timeout, or error) so the Wikimedia
 * connection is not leaked on client disconnect.
 *
 * <p>
 * Try: {@code curl -N http://localhost:2080/api/v1/wikimedia/stream}
 */
@Tag(name = "Wikimedia", description = "Wikimedia EventStreams via SSE")
@RestController
@RequestMapping("/{version}/wikimedia")
public class WikimediaController extends BaseController {

    private final WikimediaService wikimediaService;

    public WikimediaController(WikimediaService wikimediaService) {
        this.wikimediaService = wikimediaService;
    }

    @Operation(summary = "Stream Wikipedia recent changes via SSE")
    @GetMapping(value = "/stream", version = "1+", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        // 60 s for demo — avoids holding the connection open indefinitely.
        // Could use 0L (no timeout) for a true infinite stream.
        SseEmitter emitter = new SseEmitter(60_000L);

        Disposable subscription = wikimediaService.streamRecentChanges()
                .subscribe(
                        event -> {
                            try {
                                SseEmitter.SseEventBuilder builder = SseEmitter.event();
                                if (event.data() != null) {
                                    builder.data(event.data());
                                }
                                if (event.event() != null) {
                                    builder.name(event.event());
                                }
                                if (event.id() != null) {
                                    builder.id(event.id());
                                }
                                emitter.send(builder);
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        },
                        emitter::completeWithError,
                        emitter::complete);

        // Dispose the Wikimedia subscription whenever the emitter ends (timeout,
        // client disconnect, or error) — otherwise WebClient keeps the upstream
        // connection open with nowhere to send events.
        Runnable cleanup = subscription::dispose;
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ignored -> cleanup.run());

        return emitter;
    }
}
