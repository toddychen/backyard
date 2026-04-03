package com.backyard.playground.data.locale;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Locales officially supported by this service.
 *
 * <p>
 * Each constant maps to a BCP 47 language tag. Region variants are listed
 * separately when the content difference is significant enough to warrant a
 * distinct translation file — most notably {@code zh-TW} (Traditional Chinese)
 * vs {@code zh-CN} (Simplified Chinese), which use different writing scripts
 * entirely.
 *
 * <p>
 * Bare language tags ({@code en}, {@code fr}, {@code es}) cover all regional
 * variants of that language via RFC 5646 prefix matching in
 * {@link Locale#lookup}: {@code en-US}, {@code en-GB}, and {@code en-CA} all
 * resolve to {@link #EN}. Only add a region variant when you have a separate
 * translation file for it.
 *
 * <p>
 * {@link #fromLanguageTag} always returns a value — unsupported or unknown tags
 * fall back to {@link #EN}.
 */
public enum SupportedLocale {
    EN("en"),
    FR("fr"),
    ES("es"),
    ZH_TW("zh-TW"), // Traditional Chinese (Taiwan, Hong Kong)
    ZH_CN("zh-CN"), // Simplified Chinese (Mainland China)
    JA("ja"),
    DE("de"),
    PT_BR("pt-BR"); // Brazilian Portuguese

    private final String languageTag;

    // Keyed by full BCP 47 tag (lowercase) for exact lookup
    private static final Map<String, SupportedLocale> TAG_MAP = Arrays.stream(values())
            .collect(Collectors.toMap(l -> l.languageTag.toLowerCase(), l -> l));

    SupportedLocale(String languageTag) {
        this.languageTag = languageTag;
    }

    public String getLanguageTag() {
        return languageTag;
    }

    /** Returns the {@link Locale} corresponding to this entry's BCP 47 tag. */
    public Locale toLocale() {
        return Locale.forLanguageTag(languageTag);
    }

    /**
     * Resolves a BCP 47 tag to a {@link SupportedLocale}.
     *
     * <p>
     * Lookup is exact (case-insensitive). If the tag is not in the supported set,
     * returns {@link #EN} as the default. Never throws.
     *
     * <p>
     * Note: callers should pass the full resolved tag (e.g. {@code "zh-TW"}), not
     * just the language subtag ({@code "zh"}), so that Traditional and Simplified
     * Chinese resolve to distinct entries.
     */
    public static SupportedLocale fromLanguageTag(String tag) {
        if (tag == null)
            return EN;
        // Step 1: exact match (e.g. "zh-TW" → ZH_TW)
        SupportedLocale exact = TAG_MAP.get(tag.toLowerCase());
        if (exact != null)
            return exact;
        // Step 2: language-prefix fallback (e.g. "en-US" → "en" → EN).
        // Handles clients that send a region subtag for a language whose
        // supported entry has no region (e.g. en-US, en-GB → EN).
        String language = tag.split("-")[0].toLowerCase();
        return TAG_MAP.getOrDefault(language, EN);
    }
}
