package com.backyard.playground.controller;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class EchoController {
    private static final Logger log = LoggerFactory.getLogger(EchoController.class);

    public record EchoResponse(String echo, String from, String timestamp) {}

    @GetMapping("/echo")
    public EchoResponse echo(
            @RequestParam("message") String message,
            @RequestParam(value = "from", required = false) String from
    ) {
        log.info("Echo: message='{}' from='{}'", message, from);

        var response = new EchoResponse(message, from, Instant.now().toString());
        return response;
    }
}

