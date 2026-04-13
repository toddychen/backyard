package com.backyard.playground.client.reactive.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;

import reactor.core.publisher.Mono;

/**
 * Logs every outbound reactive HTTP request URL and the
 * response status. Mirrors {@code LoggingInterceptor} for
 * the WebClient stack.
 *
 * <p>
 * Register via {@link
 * org.springframework.web.reactive.function.client.WebClient.Builder#filter}
 * before calling {@code build()}.
 */
public class LoggingExchangeFilter implements ExchangeFilterFunction {

    private static final Logger log = LoggerFactory.getLogger(LoggingExchangeFilter.class);

    private final String clientName;

    private LoggingExchangeFilter(String clientName) {
        this.clientName = clientName;
    }

    public static LoggingExchangeFilter forClient(String clientName) {
        return new LoggingExchangeFilter(clientName);
    }

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        log.info("[{}] {} {}", clientName, request.method(), request.url());
        return next.exchange(request)
                .doOnNext(response -> log.info(
                        "[{}] {} {}",
                        clientName,
                        response.statusCode(),
                        request.url()));
    }
}
