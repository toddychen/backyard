package com.backyard.playground.client.rest;

import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;

public interface GooglePollenClient {

    @GetExchange("/v1/forecast:lookup")
    Object getForecast(
            @RequestParam("location.latitude") double latitude,
            @RequestParam("location.longitude") double longitude,
            @RequestParam("days") int days);
}
