package com.backyard.playground.client.rest;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

import com.backyard.playground.data.api.osrm.OsrmRouteResponse;

@HttpExchange
public interface OsrmClient {

    @GetExchange("/route/v1/driving/{coordinates}")
    OsrmRouteResponse route(
            @PathVariable String coordinates,
            @RequestParam int alternatives,
            @RequestParam String overview,
            @RequestParam String geometries);
}
