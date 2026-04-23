package com.backyard.playground.server.rest.controller;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.backyard.playground.service.driver.DriverSimulatorService;

@Profile("!home")
@RestController
@RequestMapping("/{version}/driver/simulator")
public class DriverSimulatorController extends BaseController {

    private final DriverSimulatorService simulator;

    public DriverSimulatorController(DriverSimulatorService simulator) {
        this.simulator = simulator;
    }

    @GetMapping(version = "1+")
    public ResponseEntity<String> status() {
        return ResponseEntity.ok(simulator.isRunning() ? "running" : "stopped");
    }

    @PostMapping(value = "/start", version = "1+")
    public ResponseEntity<String> start() {
        simulator.start();
        return ResponseEntity.ok("started");
    }

    @PostMapping(value = "/stop", version = "1+")
    public ResponseEntity<String> stop() {
        simulator.stop();
        return ResponseEntity.ok("stopped");
    }
}
