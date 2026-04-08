package com.backyard.playground.data.model.language;

import com.google.cloud.language.v2.AnalyzeSentimentResponse;

/**
 * @param languageCode    BCP 47 language code detected in the text
 * @param languageSupported whether the language is supported by the API
 * @param score           overall sentiment score in [-1.0, 1.0]
 *                        (negative = negative sentiment, positive = positive)
 * @param magnitude       overall strength of emotion in [0, +inf], regardless
 *                        of direction — high magnitude means strong feeling
 */
public record AnalyzeSentimentReportDTO(
        String languageCode,
        boolean languageSupported,
        float score,
        float magnitude) {

    public static AnalyzeSentimentReportDTO from(AnalyzeSentimentResponse response) {
        var sentiment = response.getDocumentSentiment();
        return new AnalyzeSentimentReportDTO(
                response.getLanguageCode(),
                response.getLanguageSupported(),
                sentiment.getScore(),
                sentiment.getMagnitude());
    }
}
