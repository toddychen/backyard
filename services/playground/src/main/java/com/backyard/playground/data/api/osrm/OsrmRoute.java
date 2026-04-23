package com.backyard.playground.data.api.osrm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OsrmRoute(
        @JsonProperty("duration") Double duration,
        @JsonProperty("distance") Double distance,
        @JsonProperty("geometry") OsrmGeometry geometry) {
}
