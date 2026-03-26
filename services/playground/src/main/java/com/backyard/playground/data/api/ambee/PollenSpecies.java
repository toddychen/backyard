package com.backyard.playground.data.api.ambee;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PollenSpecies(
        @JsonProperty("Grass") Map<String, Integer> grass,
        @JsonProperty("Tree") Map<String, Integer> tree,
        @JsonProperty("Weed") Map<String, Integer> weed,
        @JsonProperty("Others") int others) {
}
