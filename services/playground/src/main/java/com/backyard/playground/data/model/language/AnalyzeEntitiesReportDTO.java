package com.backyard.playground.data.model.language;

import com.google.cloud.language.v2.AnalyzeEntitiesResponse;

import java.util.List;

/**
 * @param languageCode    BCP 47 language code detected in the text
 * @param languageSupported whether the language is supported by the API
 * @param entities        list of entities identified in the text
 */
public record AnalyzeEntitiesReportDTO(
        String languageCode,
        boolean languageSupported,
        List<EntityDTO> entities) {

    /**
     * @param name      canonical name of the entity (e.g. "Apple Inc.")
     * @param type      entity type (PERSON, LOCATION, ORGANIZATION, etc.)
     * @param score     sentiment score for this entity in [-1.0, 1.0]
     * @param magnitude sentiment magnitude for this entity in [0, +inf]
     */
    public record EntityDTO(String name, String type, float score, float magnitude) {
    }

    public static AnalyzeEntitiesReportDTO from(AnalyzeEntitiesResponse response) {
        List<EntityDTO> entities = response.getEntitiesList().stream()
                .map(e -> new EntityDTO(
                        e.getName(),
                        e.getType().name(),
                        e.getSentiment().getScore(),
                        e.getSentiment().getMagnitude()))
                .toList();
        return new AnalyzeEntitiesReportDTO(
                response.getLanguageCode(),
                response.getLanguageSupported(),
                entities);
    }
}
