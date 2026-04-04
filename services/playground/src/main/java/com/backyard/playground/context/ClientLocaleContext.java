package com.backyard.playground.context;

import com.backyard.playground.data.model.locale.SupportedLocaleDTO;

import java.util.Objects;

/**
 * Immutable snapshot of all locale signals resolved from a single inbound
 * request. One instance is created per request by
 * {@link com.backyard.playground.filter.LocaleFilter} and stored in
 * {@link ClientLocaleContextHolder} for the duration of the request.
 *
 * <h3>Fields</h3>
 *
 * <ul>
 * <li>{@code raw} — the original {@code Accept-Language} header value as
 * sent by the client (e.g. {@code "en-CA,en;q=0.9,fr;q=0.8"}).
 * {@code null} when the header is absent.
 * <li>{@code supportedLocale} — the resolved {@link SupportedLocaleDTO}
 * after RFC 5646 lookup and fallback. Never {@code null}; defaults to
 * {@link SupportedLocaleDTO#EN} when the header is absent or no supported
 * locale matches.
 * <li>{@code region} — the ISO 3166-1 alpha-2 country code extracted from
 * the best-matching locale tag (e.g. {@code "CA"} from {@code "en-CA"}).
 * {@code null} when no region subtag is present (e.g. bare {@code "fr"}
 * or {@code "en"}).
 * </ul>
 *
 * <h3>Locale vs region</h3>
 *
 * <p>
 * {@code supportedLocale} drives language selection (which translation
 * file to use, which language tag to forward to upstream APIs).
 * {@code region} is a separate signal for content targeting — e.g. US vs
 * Canadian sports news — and is {@code null} when the client did not
 * provide a region subtag.
 */
public record ClientLocaleContext(
        String raw, SupportedLocaleDTO supportedLocale, String region) {

    // Compact constructor — validates supportedLocale is never null
    public ClientLocaleContext {
        Objects.requireNonNull(supportedLocale, "supportedLocale must not be null");
    }

    /**
     * Returns the BCP 47 language tag of the resolved supported locale
     * (e.g. {@code "en"}, {@code "zh-TW"}, {@code "pt-BR"}).
     *
     * <p>
     * Convenience shorthand for
     * {@code supportedLocale().getLanguageTag()}.
     */
    public String supportedLanguageTag() {
        return supportedLocale.getLanguageTag();
    }
}
