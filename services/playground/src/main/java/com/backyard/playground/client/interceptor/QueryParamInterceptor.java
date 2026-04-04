package com.backyard.playground.client.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.secretmanager.v1.AccessSecretVersionResponse;
import com.google.cloud.secretmanager.v1.SecretManagerServiceClient;
import com.google.cloud.secretmanager.v1.SecretVersionName;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.support.HttpRequestWrapper;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Appends query parameters to every outbound request.
 *
 * <p>
 * Two modes:
 *
 * <ul>
 * <li><b>Fixed params</b> — supply a {@code Map} of key/value pairs known at
 * construction time (e.g. {@code source=playground}).
 * <li><b>API key</b> — supply a secret name, Spring {@code Environment}, local
 * secrets file path, and GCP project ID. The secret is loaded once at startup
 * from a local JSON file when the {@code dev} profile is active, or from GCP
 * Secret Manager otherwise.
 * </ul>
 */
public class QueryParamInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(QueryParamInterceptor.class);

    private final Map<String, String> params;

    /**
     * Fixed params constructor — appends the given key/value pairs to every
     * request.
     */
    private QueryParamInterceptor(Map<String, String> params) {
        this.params = Map.copyOf(params);
    }

    /**
     * Creates an interceptor that appends fixed query params to every request.
     *
     * @param params key/value pairs to append (e.g. {@code source=playground})
     */
    public static QueryParamInterceptor build(Map<String, String> params) {
        return new QueryParamInterceptor(params);
    }

    /**
     * Creates an interceptor that appends both fixed query params and an API key
     * loaded from a local secrets file (dev) or GCP Secret Manager (stage/prod).
     *
     * @param params          fixed key/value query parameters appended to every
     *                        request (e.g. {@code
     *     source=playground})
     * @param apiKeyParamName query parameter name for the API key (e.g.
     *                        {@code "key"})
     * @param secretName      key name in the local JSON file and Secret Manager
     * @param environment     Spring environment used to detect active profiles
     * @param localFilePath   path to the local secrets JSON file (dev only)
     * @param gcpProjectId    GCP project ID for Secret Manager (stage/prod only)
     */
    public static QueryParamInterceptor withApiKey(
            Map<String, String> params,
            String apiKeyParamName,
            String secretName,
            Environment environment,
            String localFilePath,
            String gcpProjectId) {
        boolean local = Arrays.stream(environment.getActiveProfiles())
                .anyMatch(p -> p.equals("dev") || p.equals("home"));
        String apiKeyValue = local
                ? loadApiKeyFromFile(secretName, localFilePath)
                : loadApiKeyFromSecretManager(secretName, gcpProjectId);
        HashMap<String, String> merged = new HashMap<>(params);
        merged.put(apiKeyParamName, apiKeyValue);
        return new QueryParamInterceptor(merged);
    }

    /**
     * Creates an interceptor that appends only an API key, with no additional fixed
     * params.
     *
     * @param apiKeyParamName query parameter name for the API key (e.g.
     *                        {@code "key"})
     * @param secretName      key name in the local JSON file and Secret Manager
     * @param environment     Spring environment used to detect active profiles
     * @param localFilePath   path to the local secrets JSON file (dev only)
     * @param gcpProjectId    GCP project ID for Secret Manager (stage/prod only)
     */
    public static QueryParamInterceptor onlyApiKey(
            String apiKeyParamName,
            String secretName,
            Environment environment,
            String localFilePath,
            String gcpProjectId) {
        return withApiKey(
                Map.of(), apiKeyParamName, secretName, environment, localFilePath, gcpProjectId);
    }

    @Override
    public ClientHttpResponse intercept(
            HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUri(request.getURI());
        params.forEach(builder::queryParam);
        URI uri = builder.build().toUri();
        return execution.execute(
                new HttpRequestWrapper(request) {
                    @Override
                    public URI getURI() {
                        return uri;
                    }
                },
                body);
    }

    private static String loadApiKeyFromFile(String secretName, String filePath) {
        try {
            File secretsFile = new File(System.getProperty("user.dir"), filePath).getCanonicalFile();
            @SuppressWarnings("unchecked")
            Map<String, Object> secrets = new ObjectMapper().readValue(secretsFile, Map.class);
            String value = (String) secrets.get(secretName);
            if (StringUtils.isBlank(value)) {
                log.error("'{}' is blank in {}", secretName, filePath);
                return "";
            }
            log.info("Loaded '{}' from {}", secretName, filePath);
            return value;
        } catch (Exception e) {
            log.error("Could not read '{}' from {}: {}", secretName, filePath, e.getMessage());
            return "";
        }
    }

    private static String loadApiKeyFromSecretManager(String secretName, String gcpProjectId) {
        try (SecretManagerServiceClient client = SecretManagerServiceClient.create()) {
            SecretVersionName versionName = SecretVersionName.of(gcpProjectId, secretName, "latest");
            AccessSecretVersionResponse response = client.accessSecretVersion(versionName);
            String value = response.getPayload().getData().toString(StandardCharsets.UTF_8);
            if (StringUtils.isBlank(value)) {
                log.error("'{}' is blank in Secret Manager", secretName);
                return "";
            }
            log.info("Loaded '{}' from GCP Secret Manager", secretName);
            return value;
        } catch (Exception e) {
            log.error(
                    "Could not load '{}' from GCP Secret Manager: {}", secretName, e.getMessage());
            return "";
        }
    }
}
