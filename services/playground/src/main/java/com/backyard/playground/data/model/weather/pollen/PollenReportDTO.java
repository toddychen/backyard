package com.backyard.playground.data.model.weather.pollen;

public record PollenReportDTO(
        double lat,
        double lng,
        String updatedAt,
        PollenCategoryDTO grass,
        PollenCategoryDTO tree,
        PollenCategoryDTO weed) {
}
