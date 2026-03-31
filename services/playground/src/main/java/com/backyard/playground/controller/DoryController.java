package com.backyard.playground.controller;

import java.util.UUID;

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

import com.backyard.playground.data.dory.ReminderRequest;
import com.backyard.playground.data.dory.ReminderStatus;
import com.backyard.playground.service.DoryService;

@RestController
@RequestMapping("/{version}/dory/reminders")
public class DoryController extends BaseController {

    private final DoryService doryService;

    public DoryController(DoryService doryService) {
        this.doryService = doryService;
    }

    @GetMapping(version = "1+")
    public ResponseEntity<Object> getReminders(
            @RequestParam String owner,
            @RequestParam(defaultValue = "LIVE") ReminderStatus status) {
        return ok(doryService.findAll(owner, status));
    }

    @PostMapping(version = "1+")
    public ResponseEntity<Object> createReminder(
            @RequestParam String owner,
            @RequestBody ReminderRequest req) {
        return ResponseEntity.status(201).body(doryService.create(owner, req));
    }

    @PutMapping(value = "/{id}", version = "1+")
    public ResponseEntity<Object> updateReminder(
            @PathVariable UUID id,
            @RequestBody ReminderRequest req) {
        return ok(doryService.update(id, req));
    }

    @PatchMapping(value = "/{id}/done", version = "1+")
    public ResponseEntity<Object> markDone(@PathVariable UUID id) {
        return ok(doryService.markDone(id));
    }

    @PatchMapping(value = "/{id}/snooze", version = "1+")
    public ResponseEntity<Object> snooze(
            @PathVariable UUID id,
            @RequestParam int minutes) {
        return ok(doryService.snooze(id, minutes));
    }

    @DeleteMapping(value = "/{id}", version = "1+")
    public ResponseEntity<Object> deleteReminder(@PathVariable UUID id) {
        doryService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
