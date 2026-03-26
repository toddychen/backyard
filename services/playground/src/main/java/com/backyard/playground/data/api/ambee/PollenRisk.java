package com.backyard.playground.data.api.ambee;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PollenRisk(
        @JsonProperty("grass_pollen") String grassPollen,
        @JsonProperty("tree_pollen") String treePollen,
        @JsonProperty("weed_pollen") String weedPollen) {
}
