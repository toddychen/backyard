package com.backyard.playground.data.api.osrm;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OsrmRouteResponse(
        @JsonProperty("code") String code,
        @JsonProperty("routes") List<OsrmRoute> routes) {
}
