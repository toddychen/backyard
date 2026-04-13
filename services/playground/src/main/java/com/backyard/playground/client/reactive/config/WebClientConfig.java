package com.backyard.playground.client.reactive.config;

import com.backyard.playground.client.reactive.WikimediaStreamClient;
import com.backyard.playground.client.reactive.filter.LoggingExchangeFilter;

import io.micrometer.observation.ObservationRegistry;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Wires all reactive (WebClient-based) outbound client beans.
 *
 * <p>
 * Spring Boot 4.x does not auto-configure a {@link WebClient.Builder} bean in
 * a Spring MVC application, so the builder is created directly via the static
 * factory {@link WebClient#builder()}. {@code DefaultWebClientBuilder} defaults
 * its {@link ObservationRegistry} to {@code NOOP}, so the Spring-managed
 * registry must be injected and wired explicitly to enable tracing. Each client
 * adds its own base URL and logging filter.
 *
 * <p>
 * The server stack remains Spring MVC (Tomcat). WebClient is used only as an
 * outbound HTTP client, not as a server runtime.
 */
@Configuration
public class WebClientConfig {

    @Bean
    public WikimediaStreamClient wikimediaStreamClient(ObservationRegistry observationRegistry) {
        WebClient webClient = WebClient.builder()
                .observationRegistry(observationRegistry)
                .baseUrl("https://stream.wikimedia.org")
                .filter(LoggingExchangeFilter.forClient("wikimedia"))
                .build();
        return new WikimediaStreamClient(webClient);
    }
}
