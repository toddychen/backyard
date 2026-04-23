package com.backyard.playground.data.api.osrm;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OsrmGeometry(@JsonProperty("coordinates") List<List<Double>> coordinates) {
}
