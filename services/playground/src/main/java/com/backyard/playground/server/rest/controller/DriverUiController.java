package com.backyard.playground.server.rest.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DriverUiController {

    @GetMapping({"/driver", "/driver/"})
    public String driverPage() {
        return "forward:/driver/index.html";
    }
}
