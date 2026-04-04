package com.backyard.playground.filter;

import com.backyard.playground.context.ClientLocaleContext;
import com.backyard.playground.context.ClientLocaleContextHolder;
import com.backyard.playground.data.model.locale.SupportedLocaleDTO;

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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Resolves the caller's {@code Accept-Language} header into a
 * {@link ClientLocaleContext} and makes it available for the duration of
 * the request via {@link ClientLocaleContextHolder} and Spring's built-in
 * {@link LocaleContextHolder}.
 *
 * <h3>Resolution algorithm</h3>
 *
 * <ol>
 * <li>Parse the {@code Accept-Language} header using RFC 5646
 * {@link Locale.LanguageRange#parse}. This handles priority lists (e.g.
 * {@code "zh-TW,zh;q=0.9,en;q=0.8"}) correctly.
 * <li>Run {@link Locale#lookup} against the set of
 * {@link SupportedLocaleDTO} candidates. Lookup uses BCP 47 prefix
 * matching — {@code en-US} matches {@code en}, and {@code zh-TW} matches
 * {@code zh-TW} before falling back to {@code zh-CN}.
 * <li>If no match is found (unsupported language) or the header is
 * absent, fall back to {@link SupportedLocaleDTO#EN}.
 * </ol>
 *
 * <h3>Thread-local propagation</h3>
 *
 * <p>
 * {@link ClientLocaleContextHolder} uses {@link InheritableThreadLocal},
 * so virtual threads spawned by {@code FanOut} automatically inherit the
 * resolved context at creation time — no extra wiring needed.
 *
 * <h3>Response headers</h3>
 *
 * <ul>
 * <li>{@code Vary: Accept-Language} — tells HTTP caches (CDNs, proxies)
 * that the response varies by the request locale, preventing a cached
 * French response from being served to an English requester.
 * <li>{@code Content-Language: <tag>} — tells the client which language
 * the response body is actually in.
 * </ul>
 *
 * <p>
 * Runs at {@code @Order(0)}, before {@link AccessLogFilter}
 * ({@code @Order(1)}), so the resolved {@code locale} MDC key is
 * available in every log line written during the request.
 */
@Component
@Order(0)
public class LocaleFilter implements Filter {

    private static final String MDC_LOCALE = "locale";

    // Pre-built candidate list — one Locale per SupportedLocaleDTO constant.
    // Used by Locale.lookup() on every request.
    private static final List<Locale> CANDIDATES =
            Arrays.stream(SupportedLocaleDTO.values())
                    .map(SupportedLocaleDTO::toLocale).toList();

    @Override
    public void doFilter(
            ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;

        String header = req.getHeader(HttpHeaders.ACCEPT_LANGUAGE);
        SupportedLocaleDTO resolved = resolve(header);

        // Extract region from the client's raw input, not the resolved
        // supported locale. resolved.toLocale() has no country (e.g. "en"
        // from "en-US") because our SupportedLocaleDTO candidates use bare
        // language tags — Locale.lookup() strips the region subtag during
        // matching.
        String region = extractRegion(header, resolved);

        ClientLocaleContext ctx = new ClientLocaleContext(header, resolved, region);

        // ClientLocaleContextHolder uses InheritableThreadLocal — virtual
        // threads spawned by FanOut inherit this value at creation time
        // from the submitting request thread. See ClientLocaleContextHolder
        // for the full explanation.
        ClientLocaleContextHolder.set(ctx);

        // MDC key "locale" is emitted as a top-level JSON field by
        // logstash-logback-encoder and is visible in every log line for
        // this request, including logs from service and client layers.
        MDC.put(MDC_LOCALE, resolved.getLanguageTag());

        res.setHeader(HttpHeaders.VARY, HttpHeaders.ACCEPT_LANGUAGE);
        res.setHeader("Content-Language", resolved.getLanguageTag());

        try {
            chain.doFilter(request, response);
        } finally {
            ClientLocaleContextHolder.clear();
            MDC.remove(MDC_LOCALE);
        }
    }

    /**
     * Resolves the raw {@code Accept-Language} header value to a
     * {@link SupportedLocaleDTO} using RFC 5646 BCP 47 lookup with
     * fallback to {@link SupportedLocaleDTO#EN}.
     */
    private static SupportedLocaleDTO resolve(String acceptLanguageHeader) {
        if (acceptLanguageHeader == null || acceptLanguageHeader.isBlank()) {
            return SupportedLocaleDTO.EN;
        }
        try {
            List<Locale.LanguageRange> ranges =
                    Locale.LanguageRange.parse(acceptLanguageHeader);
            Locale matched = Locale.lookup(ranges, CANDIDATES);
            if (matched == null)
                return SupportedLocaleDTO.EN;
            // Use the full BCP 47 tag (e.g. "zh-TW") for lookup so that
            // Traditional and Simplified Chinese resolve to distinct
            // entries.
            return SupportedLocaleDTO.fromLanguageTag(matched.toLanguageTag());
        } catch (IllegalArgumentException e) {
            // Malformed Accept-Language header — fall back to English
            return SupportedLocaleDTO.EN;
        }
    }

    /**
     * Extracts the ISO 3166-1 alpha-2 region from the range in the
     * {@code Accept-Language} header that produced the resolved locale
     * (e.g. {@code "CA"} from {@code "zh,en-CA;q=0.9"} when {@code zh}
     * is unsupported and the match falls through to {@code en-CA}).
     *
     * <p>
     * We scan ranges in priority order and return the country subtag from
     * the first range whose language matches the resolved locale's
     * language. This correctly handles cases where the matching range is
     * not the first one — e.g. if the client prefers {@code zh} but we
     * resolve to {@code EN} via {@code en-CA}, the region should be
     * {@code "CA"}, not {@code null}.
     *
     * <p>
     * Returns {@code null} when no matching range has a region subtag.
     */
    private static String extractRegion(
            String acceptLanguageHeader, SupportedLocaleDTO resolved) {
        if (acceptLanguageHeader == null || acceptLanguageHeader.isBlank())
            return null;
        try {
            List<Locale.LanguageRange> ranges =
                    Locale.LanguageRange.parse(acceptLanguageHeader);
            String resolvedLang = resolved.toLocale().getLanguage();
            for (Locale.LanguageRange range : ranges) {
                Locale rangeLocale = Locale.forLanguageTag(range.getRange());
                if (rangeLocale.getLanguage().equals(resolvedLang)) {
                    String country = rangeLocale.getCountry();
                    return country.isBlank() ? null : country;
                }
            }
        } catch (IllegalArgumentException e) {
            // malformed header — no region
        }
        return null;
    }
}
