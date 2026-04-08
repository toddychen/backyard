package com.backyard.playground.server.rest.controller;

import com.backyard.playground.client.grpc.GoogleLanguageClient;
import com.backyard.playground.data.model.language.AnalyzeEntitiesReportDTO;
import com.backyard.playground.data.model.language.AnalyzeSentimentReportDTO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Language", description = "Google Cloud Natural Language API")
@RestController
@RequestMapping("/{version}/language")
public class LanguageController {

    private final GoogleLanguageClient languageClient;

    public LanguageController(GoogleLanguageClient languageClient) {
        this.languageClient = languageClient;
    }

    @Operation(summary = "Analyze sentiment of the given text")
    @GetMapping(value = "/sentiment", version = "1+")
    public ResponseEntity<AnalyzeSentimentReportDTO> sentiment(@RequestParam("text") String text) {
        return ResponseEntity.ok(AnalyzeSentimentReportDTO.from(languageClient.analyzeSentiment(text)));
    }

    @Operation(summary = "Identify entities in the given text")
    @GetMapping(value = "/entities", version = "1+")
    public ResponseEntity<AnalyzeEntitiesReportDTO> entities(@RequestParam("text") String text) {
        return ResponseEntity.ok(AnalyzeEntitiesReportDTO.from(languageClient.analyzeEntities(text)));
    }
}
