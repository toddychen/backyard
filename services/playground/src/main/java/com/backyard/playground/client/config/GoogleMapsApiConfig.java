package com.backyard.playground.client.config;

import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import com.backyard.playground.client.GooglePollenClient;
import com.backyard.playground.client.error.ClientStatusHandlers;
import com.backyard.playground.client.interceptor.LoggingInterceptor;
import com.backyard.playground.client.interceptor.QueryParamInterceptor;

import io.micrometer.observation.ObservationRegistry;

@Configuration
public class GoogleMapsApiConfig {

    private static final String API_KEY_PARAM = "key";
    private static final String KEY_NAME = "google_maps_api_key";
    private static final String LOCAL_SECRETS_PATH = "../../secrets/api_keys.json";

    @Bean
    public GooglePollenClient googlePollenClient(
            JdkClientHttpRequestFactory httpRequestFactory,
            ObservationRegistry observationRegistry,
            Environment environment,
            @Value("${gcp.project-id:}") String gcpProjectId) {
        RestClient restClient = RestClient.builder()
                .observationRegistry(observationRegistry)
                .baseUrl("https://pollen.googleapis.com")
                .requestFactory(httpRequestFactory)
                .requestInterceptor(QueryParamInterceptor.onlyApiKey(API_KEY_PARAM, KEY_NAME, environment,
                        LOCAL_SECRETS_PATH, gcpProjectId))
                .requestInterceptor(LoggingInterceptor.withRedactedParams(Set.of(API_KEY_PARAM)))
                .defaultStatusHandler(HttpStatusCode::isError, ClientStatusHandlers::handleError)
                .build();

        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(GooglePollenClient.class);
    }
}
