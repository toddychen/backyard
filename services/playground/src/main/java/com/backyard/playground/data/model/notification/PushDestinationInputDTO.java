package com.backyard.playground.data.model.notification;

/**
 * Input for registering or updating a push destination.
 *
 * <p>For PUT (token rotation), supply both {@code oldPushToken} and
 * {@code pushToken}. For POST (new registration), {@code oldPushToken} is null.
 */
public record PushDestinationInputDTO(
        /** New (or initial) push token. */
        String pushToken,
        /**
         * Previous push token — required for PUT (token rotation) only.
         * Null for initial registration.
         */
        String oldPushToken,
        /** 'APNS' or 'FCM'. */
        String platform,
        /** APNS only — app bundle ID (e.g. com.example.app). */
        String bundleId,
        /** APNS only — 'SANDBOX' or 'PRODUCTION'. */
        String apnsEnv,
        /** BCP-47 locale (e.g. en-US). */
        String locale,
        /** Whether the user has notifications enabled. */
        boolean notificationsEnabled) {
}
