package com.backyard.playground.server.rest.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public class DoryUiController {

    // Regex excludes dots — prevents matching /dory/index.html when Spring
    // re-dispatches the forward, which would cause an infinite loop.
    @GetMapping("/dory/{owner:[a-zA-Z0-9_-]+}")
    public String doryPage(@PathVariable String owner) {
        return "forward:/dory/index.html";
    }
}
