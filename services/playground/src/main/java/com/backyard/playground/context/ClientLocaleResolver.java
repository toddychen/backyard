package com.backyard.playground.context;

import com.backyard.playground.data.model.locale.SupportedLocaleDTO;

import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Shared BCP 47 locale resolution logic used by both the REST
 * {@code LocaleFilter} and the gRPC {@code GrpcLocaleInterceptor}.
 */
public final class ClientLocaleResolver {

    // Pre-built candidate list — one Locale per SupportedLocaleDTO constant.
    private static final List<Locale> CANDIDATES = Arrays.stream(SupportedLocaleDTO.values())
            .map(SupportedLocaleDTO::toLocale).toList();

    private ClientLocaleResolver() {
    }

    /**
     * Resolves a raw {@code Accept-Language} header value to a
     * {@link SupportedLocaleDTO} using RFC 5646 BCP 47 lookup, falling back to
     * {@link SupportedLocaleDTO#EN} on no match or malformed input.
     */
    public static SupportedLocaleDTO resolve(String acceptLanguageHeader) {
        if (StringUtils.isBlank(acceptLanguageHeader)) {
            return SupportedLocaleDTO.EN;
        }
        try {
            List<Locale.LanguageRange> ranges = Locale.LanguageRange.parse(acceptLanguageHeader);
            Locale matched = Locale.lookup(ranges, CANDIDATES);
            if (matched == null)
                return SupportedLocaleDTO.EN;
            return SupportedLocaleDTO.fromLanguageTag(matched.toLanguageTag());
        } catch (IllegalArgumentException e) {
            return SupportedLocaleDTO.EN;
        }
    }

    /**
     * Extracts the ISO 3166-1 alpha-2 region from the range in the
     * {@code Accept-Language} header that produced the resolved locale. Returns
     * {@code null} when no matching range has a region subtag.
     */
    public static String extractRegion(String acceptLanguageHeader, SupportedLocaleDTO resolved) {
        if (StringUtils.isBlank(acceptLanguageHeader))
            return null;
        try {
            List<Locale.LanguageRange> ranges = Locale.LanguageRange.parse(acceptLanguageHeader);
            String resolvedLang = resolved.toLocale().getLanguage();
            for (Locale.LanguageRange range : ranges) {
                Locale rangeLocale = Locale.forLanguageTag(range.getRange());
                if (rangeLocale.getLanguage().equals(resolvedLang)) {
                    String country = rangeLocale.getCountry();
                    return StringUtils.isBlank(country) ? null : country;
                }
            }
        } catch (IllegalArgumentException e) {
            // malformed header — no region
        }
        return null;
    }

    /** Builds a {@link ClientLocaleContext} from a raw Accept-Language value. */
    public static ClientLocaleContext buildContext(String acceptLanguageHeader) {
        SupportedLocaleDTO resolved = resolve(acceptLanguageHeader);
        String region = extractRegion(acceptLanguageHeader, resolved);
        return new ClientLocaleContext(acceptLanguageHeader, resolved, region);
    }
}
