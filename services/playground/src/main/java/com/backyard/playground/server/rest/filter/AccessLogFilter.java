package com.backyard.playground.server.rest.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Logs one access log line per request in Tomcat's Combined Log Format, and
 * sets MDC fields so logstash-logback-encoder emits them as top-level JSON
 * fields in the structured log output.
 *
 * <h3>Two outputs from one filter</h3>
 *
 * <p>
 * <b>message field</b> — Tomcat Combined Log Format, human-readable in local
 * dev:
 *
 * <pre>
 *   10.244.1.1 - - [28/Mar/2026:10:00:00 +0000] "GET /api/v1/sport/... HTTP/1.1" 200 - 143
 * </pre>
 *
 * <p>
 * <b>MDC fields</b> — emitted as top-level JSON fields by
 * logstash-logback-encoder, queryable in Loki via {@code | json}:
 *
 * <pre>
 *   http.method, http.uri, http.status, http.duration_ms
 * </pre>
 *
 * <h3>Why MDC and not a plain JSON message</h3>
 *
 * <p>
 * logstash-logback-encoder serializes MDC entries as top-level fields in the
 * JSON log line. This allows Loki's {@code | json} pipeline to parse them
 * directly without regex extraction. A JSON string embedded inside the
 * {@code message} field would require an extra extraction step.
 */
@Component
@Order(1)
public class AccessLogFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(AccessLogFilter.class);

    // MDC key constants — used by Loki | json queries
    private static final String MDC_METHOD = "http.method";
    private static final String MDC_URI = "http.uri";
    private static final String MDC_STATUS = "http.status";
    private static final String MDC_DURATION_MS = "http.duration_ms";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;
        long start = System.currentTimeMillis();

        try {
            chain.doFilter(request, response);
        } finally {
            long durationMs = System.currentTimeMillis() - start;
            String method = req.getMethod();
            String uri = req.getRequestURI();
            String query = req.getQueryString();
            int status = res.getStatus();
            String remote = req.getRemoteAddr();

            String fullUri = query != null ? uri + "?" + query : uri;

            // MDC fields become top-level JSON fields via logstash-logback-encoder.
            // Loki query: {app="playground"} | json | `http.uri` =~ "/sport/.*"
            MDC.put(MDC_METHOD, method);
            MDC.put(MDC_URI, fullUri);
            MDC.put(MDC_STATUS, String.valueOf(status));
            MDC.put(MDC_DURATION_MS, String.valueOf(durationMs));
            try {
                // Tomcat Combined Log Format — readable in local dev console
                // format: host - - [timestamp] "method uri protocol" status - duration_ms
                // locale=tag
                // locale is set by LocaleFilter (@Order(0)) before this filter runs
                log.info(
                        "{} - - [{}] \"{} {} {}\" {} - {} locale={}",
                        remote,
                        java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
                                .format(
                                        java.time.format.DateTimeFormatter.ofPattern(
                                                "dd/MMM/yyyy:HH:mm:ss Z")),
                        method,
                        fullUri,
                        req.getProtocol(),
                        status,
                        durationMs,
                        MDC.get("locale"));
            } finally {
                MDC.remove(MDC_METHOD);
                MDC.remove(MDC_URI);
                MDC.remove(MDC_STATUS);
                MDC.remove(MDC_DURATION_MS);
            }
        }
    }
}
