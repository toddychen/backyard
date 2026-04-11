package com.backyard.notification.common.kafka.message;

import java.util.UUID;

/** Push destination info required by the sender. */
public class PushDestinationInfo {

    private UUID userId;
    private String pushToken;
    /** 'APNS' or 'FCM'. */
    private String platform;
    /** APNS only — app bundle ID (e.g. com.example.app). Null for FCM. */
    private String bundleId;
    /** APNS only — 'SANDBOX' or 'PRODUCTION'. Null for FCM. */
    private String apnsEnv;

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getPushToken() {
        return pushToken;
    }

    public void setPushToken(String pushToken) {
        this.pushToken = pushToken;
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public String getBundleId() {
        return bundleId;
    }

    public void setBundleId(String bundleId) {
        this.bundleId = bundleId;
    }

    public String getApnsEnv() {
        return apnsEnv;
    }

    public void setApnsEnv(String apnsEnv) {
        this.apnsEnv = apnsEnv;
    }
}
