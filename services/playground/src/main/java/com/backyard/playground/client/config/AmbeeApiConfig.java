package com.backyard.playground.client.config;

import java.net.http.HttpClient;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import com.backyard.playground.client.AmbeePollenClient;
import com.backyard.playground.client.error.ClientStatusHandlers;
import com.backyard.playground.client.interceptor.LoggingInterceptor;

@Configuration
public class AmbeeApiConfig {

    // Shared JDK HTTP client — registered here and injected into the other
    // client configs. Version.HTTP_2 enables ALPN negotiation on TLS: the
    // JVM advertises h2 in the ClientHello and uses HTTP/2 if the server
    // agrees, falling back to HTTP/1.1 automatically otherwise.
    @Bean
    public JdkClientHttpRequestFactory httpRequestFactory() {
        var jdkClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .build();
        return new JdkClientHttpRequestFactory(jdkClient);
    }

    @Bean
    public AmbeePollenClient ambeePollenClient(
            JdkClientHttpRequestFactory httpRequestFactory,
            ObservationRegistry observationRegistry) {
        RestClient restClient = RestClient.builder()
                .observationRegistry(observationRegistry)
                .baseUrl("https://ambee-maps-backend.ambeedata.com")
                .requestFactory(httpRequestFactory)
                .defaultHeader("Referer", "https://maps.getambee.com/")
                .defaultHeader("Origin", "https://maps.getambee.com")
                .defaultHeader("User-Agent",
                        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36")
                .defaultHeader("Accept", "application/json, text/plain, */*")
                .defaultHeader("Accept-Language", "en-US,en;q=0.9,zh-CN;q=0.8,zh;q=0.7")
                .defaultHeader("sec-ch-ua",
                        "\"Chromium\";v=\"146\", \"Not-A.Brand\";v=\"24\", \"Google Chrome\";v=\"146\"")
                .defaultHeader("sec-ch-ua-mobile", "?0")
                .defaultHeader("sec-ch-ua-platform", "\"macOS\"")
                .defaultHeader("sec-fetch-dest", "empty")
                .defaultHeader("sec-fetch-mode", "cors")
                .defaultHeader("sec-fetch-site", "cross-site")
                .requestInterceptor(LoggingInterceptor.noRedaction())
                .defaultStatusHandler(HttpStatusCode::isError, ClientStatusHandlers::handleError)
                .build();

        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(AmbeePollenClient.class);
    }
}
