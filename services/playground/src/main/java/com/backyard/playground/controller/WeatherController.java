package com.backyard.playground.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backyard.playground.service.WeatherService;

@RestController
@RequestMapping("/{version}/weather")
public class WeatherController extends BaseController {

    private final WeatherService weatherService;

    public WeatherController(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    @GetMapping(value = "/pollen", version = "1+")
    public ResponseEntity<Object> pollen(@RequestParam("city") String city) {
        return ok(weatherService.getPollenByCity(city));
    }
}
