package com.backyard.playground.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backyard.playground.service.EchoService;

@RestController
@RequestMapping("/{version}/echo")
public class EchoController extends BaseController {

    private final EchoService echoService;

    public EchoController(EchoService echoService) {
        this.echoService = echoService;
    }

    @GetMapping(version = "1+")
    public ResponseEntity<Object> echo(
            @RequestParam("message") String message,
            @RequestParam(value = "from", required = false) String from) {
        return ok(echoService.echo(message, from));
    }
}
