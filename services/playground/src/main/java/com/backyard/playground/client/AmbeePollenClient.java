package com.backyard.playground.client;

import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;

import com.backyard.playground.data.api.ambee.AmbeePollenResponse;

public interface AmbeePollenClient {

    @GetExchange("/v1/pollen/latest")
    AmbeePollenResponse getLatestPollen(
            @RequestParam("lat") double lat,
            @RequestParam("lng") double lng,
            @RequestParam("speciesRisk") boolean speciesRisk);
}
