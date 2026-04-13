package com.backyard.playground.client.reactive;

import org.springframework.web.reactive.function.client.WebClient;

/**
 * Base class for reactive {@link WebClient}-based outbound clients.
 *
 * <p>
 * Subclasses receive a fully configured {@link WebClient}
 * (base URL, tracing observation filter, and logging filter
 * already applied) via constructor injection from
 * {@code WebClientConfig}.
 */
public abstract class BaseWebClient {

    protected final WebClient webClient;

    protected BaseWebClient(WebClient webClient) {
        this.webClient = webClient;
    }
}
