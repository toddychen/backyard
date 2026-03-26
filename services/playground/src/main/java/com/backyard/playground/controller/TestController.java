package com.backyard.playground.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.backyard.playground.service.TraceService;

@RestController
@RequestMapping("/{version}/test")
public class TestController extends BaseController {

    private final TraceService traceService;

    public TestController(TraceService traceService) {
        this.traceService = traceService;
    }

    @GetMapping(value = "/tracing", version = "1+")
    public ResponseEntity<Object> tracing() {
        return ok(traceService.currentTrace());
    }
}
