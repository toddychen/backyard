package com.backyard.playground.data.weather.pollen;

import java.util.Map;

public record PollenCategory(
        int count,
        String risk,
        Map<String, Integer> species) {
}
