package com.backyard.playground.data.api.ambee;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PollenData(
        @JsonProperty("Risk") PollenRisk risk,
        @JsonProperty("Count") PollenCount count,
        @JsonProperty("Species") PollenSpecies species,
        String timezone,
        String updatedAt) {
}
