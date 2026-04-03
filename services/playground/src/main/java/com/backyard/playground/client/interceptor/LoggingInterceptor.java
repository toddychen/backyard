package com.backyard.playground.client.interceptor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * Logs every outbound request URL and response status. Query param values for
 * keys in {@code
 * redactedParams} are replaced with {@code ***} in the log output.
 *
 * <p>
 * Register after {@link QueryParamInterceptor} so the fully-built URI
 * (including appended params) is logged.
 */
public class LoggingInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(LoggingInterceptor.class);
    private static final String REDACTED = "***";

    private final String clientName;
    private final Set<String> redactedParams;

    public LoggingInterceptor(String clientName, Set<String> redactedParams) {
        this.clientName = clientName;
        this.redactedParams = Set.copyOf(redactedParams);
    }

    public static LoggingInterceptor withRedactedParams(
            String clientName, Set<String> redactedParams) {
        return new LoggingInterceptor(clientName, redactedParams);
    }

    public static LoggingInterceptor noRedaction(String clientName) {
        return new LoggingInterceptor(clientName, Set.of());
    }

    @Override
    public ClientHttpResponse intercept(
            HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        log.info("[{}] {} {}", clientName, request.getMethod(), mask(request.getURI().toString()));
        return execution.execute(request, body);
    }

    private String mask(String url) {
        if (redactedParams.isEmpty())
            return url;
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>(
                UriComponentsBuilder.fromUriString(url).build().getQueryParams());
        redactedParams.forEach(
                key -> {
                    if (params.containsKey(key))
                        params.put(key, List.of(REDACTED));
                });
        return UriComponentsBuilder.fromUriString(url)
                .replaceQueryParams(params)
                .build()
                .toUriString();
    }
}
