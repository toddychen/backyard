package com.backyard.playground.data.weather.pollen;

public record PollenReport(
        double lat,
        double lng,
        String updatedAt,
        PollenCategory grass,
        PollenCategory tree,
        PollenCategory weed) {
}
