package com.backyard.playground.data.model.locale;

import java.util.Map;

/**
 * Maps each {@link SupportedLocaleDTO} to the BCP 47 tag that the Yahoo Sports API
 * accepts in its {@code Accept-Language} request header.
 *
 * <p>
 * Yahoo Sports only supports {@code en-US} and {@code es-US}. All other service
 * locales fall back to {@code "en-US"}.
 *
 * <p>
 * Usage — obtain the Yahoo Sports tag for the current request's locale:
 *
 * <pre>
 * String tag = YahooSportsLocaleDTO.tagFor(ClientLocaleContextHolder.get().supportedLocale());
 * </pre>
 */
public enum YahooSportsLocaleDTO {
    EN_US("en-US"),
    ES_US("es-US");

    private final String tag;

    private static final Map<SupportedLocaleDTO, YahooSportsLocaleDTO> SERVICE_MAP = Map.of(
            SupportedLocaleDTO.EN, EN_US,
            SupportedLocaleDTO.ES, ES_US);

    YahooSportsLocaleDTO(String tag) {
        this.tag = tag;
    }

    /**
     * Returns the Yahoo Sports BCP 47 tag for the given service locale. Falls back
     * to {@code
     * "en-US"} for any locale not supported by Yahoo.
     */
    public static String fromServiceLocale(SupportedLocaleDTO locale) {
        return SERVICE_MAP.getOrDefault(locale, EN_US).tag;
    }
}
