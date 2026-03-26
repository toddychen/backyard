package com.backyard.playground.data.api.ambee;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PollenCount(
        @JsonProperty("grass_pollen") int grassPollen,
        @JsonProperty("tree_pollen") int treePollen,
        @JsonProperty("weed_pollen") int weedPollen) {
}
