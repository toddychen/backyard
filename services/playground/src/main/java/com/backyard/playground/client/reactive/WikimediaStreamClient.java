package com.backyard.playground.client.reactive;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Flux;

/**
 * Reactive client for the Wikimedia EventStreams API.
 *
 * <p>
 * Streams recent Wikipedia changes as a never-ending {@link Flux} of
 * {@link ServerSentEvent}s. Each event carries the raw JSON payload from the
 * {@code data:} field.
 */
public class WikimediaStreamClient extends BaseWebClient {

    public WikimediaStreamClient(WebClient webClient) {
        super(webClient);
    }

    public Flux<ServerSentEvent<String>> streamRecentChanges() {
        ParameterizedTypeReference<ServerSentEvent<String>> type = new ParameterizedTypeReference<>() {
        };
        return webClient.get()
                .uri("/v2/stream/recentchange")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(type);
    }
}
