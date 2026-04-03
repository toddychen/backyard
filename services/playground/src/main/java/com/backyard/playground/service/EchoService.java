package com.backyard.playground.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class EchoService {
    private static final Logger log = LoggerFactory.getLogger(EchoService.class);

    public record EchoResult(String echo, String from, String timestamp) {
    }

    public EchoResult echo(String message, String from) {
        log.info("echo: message='{}' from='{}'", message, from);
        return new EchoResult(message, from, Instant.now().toString());
    }
}
