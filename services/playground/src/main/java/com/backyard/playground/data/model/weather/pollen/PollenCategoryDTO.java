package com.backyard.playground.data.model.weather.pollen;

import java.util.Map;

public record PollenCategoryDTO(int count, String risk, Map<String, Integer> species) {
}
