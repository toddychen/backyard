package com.backyard.playground.data.model.driver;

import java.util.List;

public record RouteDTO(Integer durationSeconds, Integer distanceMeters, List<List<Double>> coordinates) {
}
