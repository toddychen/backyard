package com.backyard.playground.server.rest.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.backyard.playground.data.model.notification.PushDestinationInputDTO;
import com.backyard.playground.data.model.notification.SubscriptionInputDTO;
import com.backyard.playground.service.notification.PushDestinationService;
import com.backyard.playground.service.notification.SubscriptionService;

import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Notification", description = "Push destinations and topic subscriptions")
@RestController
@RequestMapping("/{version}/notification")
public class NotificationController extends BaseController {

    private final PushDestinationService pushDestinationService;
    private final SubscriptionService subscriptionService;

    public NotificationController(
            PushDestinationService pushDestinationService,
            SubscriptionService subscriptionService) {
        this.pushDestinationService = pushDestinationService;
        this.subscriptionService = subscriptionService;
    }

    // -- Destinations --

    @GetMapping(value = "/destinations", version = "1+")
    public ResponseEntity<Object> listDestinations(
            @RequestHeader("MOCK_USER_ID") String mockUserId) {
        return ok(pushDestinationService.list(resolveUserId(mockUserId)));
    }

    /**
     * Register or update a push destination.
     *
     * <p>
     * If {@code oldPushToken} is present, the old row is deleted and a new one is
     * inserted (token rotation). Otherwise, the row for {@code pushToken} is
     * upserted — safe to call repeatedly with updated fields like locale.
     */
    @PostMapping(value = "/destinations", version = "1+")
    public ResponseEntity<Object> registerDestination(
            @RequestHeader("MOCK_USER_ID") String mockUserId,
            @RequestBody PushDestinationInputDTO req) {
        var result = pushDestinationService.register(resolveUserId(mockUserId), req);
        return ResponseEntity.status(201).body(result);
    }

    @DeleteMapping(value = "/destinations", version = "1+")
    public ResponseEntity<Object> deleteDestination(
            @RequestHeader("MOCK_USER_ID") String mockUserId,
            @RequestBody PushDestinationInputDTO req) {
        pushDestinationService.delete(resolveUserId(mockUserId), req.pushToken());
        return ResponseEntity.noContent().build();
    }

    // -- Subscriptions --

    @GetMapping(value = "/subscriptions", version = "1+")
    public ResponseEntity<Object> listSubscriptions(
            @RequestHeader("MOCK_USER_ID") String mockUserId) {
        return ok(subscriptionService.list(resolveUserId(mockUserId)));
    }

    @PostMapping(value = "/subscriptions", version = "1+")
    public ResponseEntity<Object> subscribe(
            @RequestHeader("MOCK_USER_ID") String mockUserId,
            @RequestBody SubscriptionInputDTO req) {
        return ResponseEntity.status(201)
                .body(subscriptionService.subscribe(resolveUserId(mockUserId), req));
    }

    @DeleteMapping(value = "/subscriptions/{topicId}", version = "1+")
    public ResponseEntity<Object> unsubscribe(
            @RequestHeader("MOCK_USER_ID") String mockUserId,
            @PathVariable String topicId) {
        subscriptionService.unsubscribe(resolveUserId(mockUserId), topicId);
        return ResponseEntity.noContent().build();
    }

    // -- Helpers --

    /**
     * Extracts the caller's user ID from the {@code MOCK_USER_ID} header.
     *
     * <p>
     * TODO: replace with {@code @AuthenticationPrincipal} once the notification
     * endpoints are gated behind JWT auth.
     */
    private UUID resolveUserId(String mockUserId) {
        return UUID.fromString(mockUserId);
    }
}
