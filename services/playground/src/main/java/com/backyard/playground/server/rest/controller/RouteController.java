package com.backyard.playground.server.rest.controller;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backyard.playground.data.model.driver.RouteResponseDTO;
import com.backyard.playground.service.driver.RouteService;

@Profile("!home")
@RestController
@RequestMapping("/{version}/route")
public class RouteController extends BaseController {

    private final RouteService routeService;

    public RouteController(RouteService routeService) {
        this.routeService = routeService;
    }

    @GetMapping(version = "1+")
    public ResponseEntity<RouteResponseDTO> getRoutes(
            @RequestParam double fromLat,
            @RequestParam double fromLng,
            @RequestParam double toLat,
            @RequestParam double toLng) {
        return ResponseEntity.ok(routeService.getRoutes(fromLat, fromLng, toLat, toLng));
    }
}
