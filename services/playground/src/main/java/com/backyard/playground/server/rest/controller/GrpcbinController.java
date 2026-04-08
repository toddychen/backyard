package com.backyard.playground.server.rest.controller;

import com.backyard.playground.client.grpc.GrpcbinClient;
import com.backyard.playground.data.model.grpcbin.ConcatResultDTO;
import com.backyard.playground.data.model.grpcbin.SumResultDTO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Grpcbin", description = "grpcb.in Add service demo")
@RestController
@RequestMapping("/{version}/grpcbin")
public class GrpcbinController {

    private final GrpcbinClient grpcbinClient;

    public GrpcbinController(GrpcbinClient grpcbinClient) {
        this.grpcbinClient = grpcbinClient;
    }

    @Operation(summary = "Sum two integers via grpcb.in")
    @GetMapping(value = "/sum", version = "1+")
    public ResponseEntity<SumResultDTO> sum(
            @RequestParam long a,
            @RequestParam long b) {
        return ResponseEntity.ok(new SumResultDTO(a, b, grpcbinClient.sum(a, b)));
    }

    @Operation(summary = "Concatenate two strings via grpcb.in")
    @GetMapping(value = "/concat", version = "1+")
    public ResponseEntity<ConcatResultDTO> concat(
            @RequestParam String a,
            @RequestParam String b) {
        return ResponseEntity.ok(new ConcatResultDTO(a, b, grpcbinClient.concat(a, b)));
    }
}
