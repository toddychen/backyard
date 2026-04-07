package com.backyard.playground.server.rest.controller;

import com.backyard.playground.data.persist.h2.dory.ReminderStatus;
import com.backyard.playground.data.model.dory.ReminderEntryInputDTO;
import com.backyard.playground.data.model.dory.ReminderEntryDTO;
import com.backyard.playground.service.DoryService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Dory", description = "Reminders (Dory)")
@RestController
@RequestMapping("/{version}/dory/reminders")
public class DoryController extends BaseController {

    private final DoryService doryService;

    public DoryController(DoryService doryService) {
        this.doryService = doryService;
    }

    @Operation(summary = "List reminders for an owner")
    @ApiResponses({
            @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = ReminderEntryDTO.class)))),
            @ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class)))
    })
    @GetMapping(version = "1+")
    public ResponseEntity<Object> getReminders(
            @RequestParam String owner,
            @RequestParam(defaultValue = "LIVE") ReminderStatus status) {
        return ok(doryService.findAll(owner, status));
    }

    @Operation(summary = "Create a reminder")
    @ApiResponses({
            @ApiResponse(responseCode = "201", content = @Content(schema = @Schema(implementation = ReminderEntryDTO.class))),
            @ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))),
            @ApiResponse(responseCode = "409", content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class)))
    })
    @PostMapping(version = "1+")
    public ResponseEntity<Object> createReminder(
            @RequestParam String owner, @RequestBody ReminderEntryInputDTO req) {
        return ResponseEntity.status(201).body(doryService.create(owner, req));
    }

    @Operation(summary = "Update a reminder")
    @ApiResponses({
            @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ReminderEntryDTO.class))),
            @ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))),
            @ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class)))
    })
    @PutMapping(value = "/{id}", version = "1+")
    public ResponseEntity<Object> updateReminder(
            @PathVariable UUID id, @RequestBody ReminderEntryInputDTO req) {
        return ok(doryService.update(id, req));
    }

    @Operation(summary = "Mark a reminder as done")
    @ApiResponses({
            @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ReminderEntryDTO.class))),
            @ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))),
            @ApiResponse(responseCode = "409", content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class)))
    })
    @PatchMapping(value = "/{id}/done", version = "1+")
    public ResponseEntity<Object> markDone(@PathVariable UUID id) {
        return ok(doryService.markDone(id));
    }

    @Operation(summary = "Snooze a reminder")
    @ApiResponses({
            @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ReminderEntryDTO.class))),
            @ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))),
            @ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class)))
    })
    @PatchMapping(value = "/{id}/snooze", version = "1+")
    public ResponseEntity<Object> snooze(@PathVariable UUID id, @RequestParam int minutes) {
        return ok(doryService.snooze(id, minutes));
    }

    @Operation(summary = "Delete a reminder")
    @ApiResponses({
            @ApiResponse(responseCode = "204"),
            @ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class)))
    })
    @DeleteMapping(value = "/{id}", version = "1+")
    public ResponseEntity<Object> deleteReminder(@PathVariable UUID id) {
        doryService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
