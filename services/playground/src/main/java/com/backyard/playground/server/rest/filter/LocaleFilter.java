package com.backyard.playground.server.rest.filter;

import com.backyard.playground.context.ClientLocaleContext;
import com.backyard.playground.context.ClientLocaleContextHolder;
import com.backyard.playground.context.ClientLocaleResolver;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Resolves the caller's {@code Accept-Language} header into a
 * {@link ClientLocaleContext} and makes it available for the duration of the
 * request via {@link ClientLocaleContextHolder}.
 *
 * <h3>Resolution algorithm</h3>
 *
 * <ol>
 * <li>Parse the {@code Accept-Language} header using RFC 5646
 * {@link java.util.Locale.LanguageRange#parse}. This handles priority lists
 * (e.g. {@code "zh-TW,zh;q=0.9,en;q=0.8"}) correctly.
 * <li>Run {@link java.util.Locale#lookup} against the set of supported locales.
 * Lookup uses BCP 47 prefix matching.
 * <li>If no match is found or the header is absent, fall back to EN.
 * </ol>
 *
 * <h3>Thread-local propagation</h3>
 *
 * <p>
 * {@link ClientLocaleContextHolder} uses {@link InheritableThreadLocal}, so
 * virtual threads spawned by {@code FanOut} automatically inherit the resolved
 * context at creation time — no extra wiring needed.
 *
 * <h3>Response headers</h3>
 *
 * <ul>
 * <li>{@code Vary: Accept-Language} — tells HTTP caches that the response
 * varies by locale.
 * <li>{@code Content-Language: <tag>} — tells the client which language the
 * response body is in.
 * </ul>
 *
 * <p>
 * Runs at {@code @Order(0)}, before {@link AccessLogFilter}
 * ({@code @Order(1)}), so the resolved {@code locale} MDC key is available in
 * every log line.
 */
@Component
@Order(0)
public class LocaleFilter implements Filter {

    private static final String MDC_LOCALE = "locale";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;

        String header = req.getHeader(HttpHeaders.ACCEPT_LANGUAGE);
        ClientLocaleContext ctx = ClientLocaleResolver.buildContext(header);
        ClientLocaleContextHolder.set(ctx);
        MDC.put(MDC_LOCALE, ctx.supportedLanguageTag());

        res.setHeader(HttpHeaders.VARY, HttpHeaders.ACCEPT_LANGUAGE);
        res.setHeader("Content-Language", ctx.supportedLanguageTag());

        try {
            chain.doFilter(request, response);
        } finally {
            ClientLocaleContextHolder.clear();
            MDC.remove(MDC_LOCALE);
        }
    }
}
