package com.backyard.playground.server.rest.controller;

import com.backyard.playground.context.ClientLocaleContext;
import com.backyard.playground.context.ClientLocaleContextHolder;
import com.backyard.playground.data.model.locale.YahooSportsLocaleDTO;
import com.backyard.playground.service.TraceService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@Tag(name = "Test", description = "Debug and inspection endpoints")
@RestController
@RequestMapping("/{version}/test")
public class TestController extends BaseController {

    private final TraceService traceService;

    public TestController(TraceService traceService) {
        this.traceService = traceService;
    }

    @Operation(summary = "Return current trace and span IDs")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = TraceService.TraceInfo.class)))
    @GetMapping(value = "/tracing", version = "1+")
    public ResponseEntity<Object> tracing() {
        return ok(traceService.currentTrace());
    }

    // curl -H "Accept-Language: en-US" localhost:2000/api/v1/test/locale
    // curl -H "Accept-Language: zh-TW,en;q=0.9" localhost:2000/api/v1/test/locale
    // curl localhost:2000/api/v1/test/locale
    @Operation(summary = "Return resolved locale from Accept-Language header")
    @ApiResponse(responseCode = "200")
    @GetMapping(value = "/locale", version = "1+")
    public ResponseEntity<Object> locale() {
        ClientLocaleContext ctx = ClientLocaleContextHolder.get();
        if (ctx == null)
            return ok(Map.of());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("raw", ctx.raw());
        result.put("supportedLocale", ctx.supportedLocale().getLanguageTag());
        result.put("region", ctx.region());
        result.put("yahooSportsLocale",
                YahooSportsLocaleDTO.fromServiceLocale(ctx.supportedLocale()));
        return ok(result);
    }
}
