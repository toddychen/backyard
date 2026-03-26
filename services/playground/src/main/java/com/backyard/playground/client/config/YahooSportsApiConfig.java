package com.backyard.playground.client.config;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import com.backyard.playground.client.YahooSportsClient;
import com.backyard.playground.client.error.ClientStatusHandlers;
import com.backyard.playground.client.interceptor.LoggingInterceptor;
import com.backyard.playground.exception.NotFoundException;

@Configuration
public class YahooSportsApiConfig {

    private static final Logger log = LoggerFactory.getLogger(YahooSportsApiConfig.class);

    @Bean
    public YahooSportsClient yahooSportsClient(
            JdkClientHttpRequestFactory httpRequestFactory,
            ObservationRegistry observationRegistry) {
        RestClient restClient = RestClient.builder()
                .observationRegistry(observationRegistry)
                .baseUrl("https://mrest.sports.yahoo.com")
                .requestFactory(httpRequestFactory)
                .defaultHeader("User-Agent",
                        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36")
                .defaultHeader("Accept", "application/json, text/plain, */*")
                .defaultHeader("Accept-Language", "en-US,en;q=0.9")
                .requestInterceptor(LoggingInterceptor.noRedaction())
                .defaultStatusHandler(HttpStatusCode::isError, ClientStatusHandlers::handleError)
                .build();

        YahooSportsClient httpProxy = HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(YahooSportsClient.class);

        return (YahooSportsClient) Proxy.newProxyInstance(
                YahooSportsClient.class.getClassLoader(),
                new Class<?>[] { YahooSportsClient.class },
                (p, method, args) -> {
                    try {
                        return method.invoke(httpProxy, args);
                    } catch (InvocationTargetException e) {
                        if (e.getCause() instanceof NotFoundException) {
                            log.warn("Yahoo Sports 404 on {}({})", method.getName(), Arrays.toString(args),
                                    e.getCause().getCause());
                            return null;
                        }
                        throw e.getCause();
                    }
                });
    }
}
