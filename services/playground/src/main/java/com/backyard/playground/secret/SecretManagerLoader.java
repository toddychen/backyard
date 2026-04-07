package com.backyard.playground.secret;

import com.google.cloud.secretmanager.v1.AccessSecretVersionResponse;
import com.google.cloud.secretmanager.v1.SecretManagerServiceClient;
import com.google.cloud.secretmanager.v1.SecretVersionName;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Loads sensitive credentials from GCP Secret Manager at startup and sets them
 * as system properties so Spring {@code @Value} and datasource
 * auto-configuration pick them up transparently.
 *
 * <p>
 * Runs on all profiles except {@code dev}, where credentials are read from
 * local properties files instead.
 *
 * <p>
 * Secrets loaded:
 *
 * <ul>
 * <li>{@code playground-jwt-secret} → {@code auth.jwt.secret}
 * <li>{@code playground-mysql-password} → {@code spring.datasource.password}
 * </ul>
 */
@Component
@Profile("!dev & !home & !kind & !ci")
public class SecretManagerLoader {

    private static final Logger log = LoggerFactory.getLogger(SecretManagerLoader.class);

    public SecretManagerLoader(@Value("${gcp.project-id}") String projectId) {
        try (SecretManagerServiceClient client = SecretManagerServiceClient.create()) {
            setProperty("auth.jwt.secret", load(client, projectId, "playground-jwt-secret"));
            setProperty("mysql.password", load(client, projectId, "playground-mysql-password"));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load secrets from GCP Secret Manager", e);
        }
    }

    private String load(SecretManagerServiceClient client, String projectId, String secretName) {
        SecretVersionName version = SecretVersionName.of(projectId, secretName, "latest");
        AccessSecretVersionResponse response = client.accessSecretVersion(version);
        String value = response.getPayload().getData().toString(StandardCharsets.UTF_8);
        log.info("loaded '{}' from GCP Secret Manager", secretName);
        return value;
    }

    private void setProperty(String key, String value) {
        System.setProperty(key, value);
    }
}
