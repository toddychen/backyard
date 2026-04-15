package com.backyard.playground.server.rest.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Forwards browser navigation requests to the chat SPA pages.
 *
 * <p>Two pages:
 * <ul>
 *   <li>{@code /chat/login} — user picker + demo registration form</li>
 *   <li>{@code /chat/app}   — main Slack-style chat UI</li>
 * </ul>
 *
 * Static assets ({@code .js}, {@code .css}) are served directly by Spring Boot
 * from {@code src/main/resources/static/} — no forwarding needed for those.
 */
@Controller
public class ChatUiController {

    @GetMapping("/chat/login")
    public String loginPage() {
        return "forward:/chat/login.html";
    }

    @GetMapping("/chat/app")
    public String appPage() {
        return "forward:/chat/app.html";
    }
}
