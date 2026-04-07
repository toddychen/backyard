package com.backyard.playground.server.rest.controller;

import com.backyard.playground.data.model.weather.pollen.PollenReportDTO;
import com.backyard.playground.service.WeatherService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Weather", description = "Pollen and weather data")
@RestController
@RequestMapping("/{version}/weather")
public class WeatherController extends BaseController {

    private final WeatherService weatherService;

    public WeatherController(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    @Operation(summary = "Get pollen report for a city")
    @ApiResponses({
            @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = PollenReportDTO.class))),
            @ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))),
            @ApiResponse(responseCode = "503", content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class)))
    })
    @GetMapping(value = "/pollen", version = "1+")
    public ResponseEntity<Object> pollen(@RequestParam("city") String city) {
        return ok(weatherService.getPollenByCity(city));
    }
}
