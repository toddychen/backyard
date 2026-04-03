package com.backyard.playground.controller;

import com.backyard.playground.context.ClientLocaleContextHolder;
import com.backyard.playground.service.EchoService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.context.MessageSource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Echo", description = "Echo endpoint for testing")
@RestController
@RequestMapping("/{version}/echo")
public class EchoController extends BaseController {

    private final EchoService echoService;
    private final MessageSource messageSource;

    public EchoController(EchoService echoService, MessageSource messageSource) {
        this.echoService = echoService;
        this.messageSource = messageSource;
    }

    @Operation(summary = "Echo a message back with metadata")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = EchoService.EchoResult.class)))
    @GetMapping(version = "1+")
    public ResponseEntity<Object> echo(
            @RequestParam("message") String message,
            @RequestParam(value = "from", required = false) String from) {
        return ok(echoService.echo(message, from));
    }

    /**
     * Returns the word "Hello" translated into the locale resolved from the
     * request's {@code
     * Accept-Language} header. Demonstrates the full locale pipeline: header →
     * LocaleFilter → ClientLocaleContext → MessageSource lookup.
     */
    @Operation(summary = "Return 'Hello' in the request locale")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = HelloResult.class)))
    @GetMapping(value = "/hello", version = "1+")
    public ResponseEntity<Object> hello() {
        var ctx = ClientLocaleContextHolder.get();
        var locale = ctx.supportedLocale().toLocale();
        var label = messageSource.getMessage("hello", null, locale);
        return ok(new HelloResult(ctx.supportedLanguageTag(), label));
    }

    public record HelloResult(String locale, String text) {
    }
}
