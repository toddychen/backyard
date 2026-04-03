package com.backyard.playground.client.config;

import com.backyard.playground.client.AmbeePollenClient;
import com.backyard.playground.client.GooglePollenClient;
import com.backyard.playground.client.YahooSportsClient;
import com.backyard.playground.client.error.ClientStatusHandlers;
import com.backyard.playground.client.interceptor.LocaleInterceptor;
import com.backyard.playground.client.interceptor.LoggingInterceptor;
import com.backyard.playground.client.interceptor.QueryParamInterceptor;
import com.backyard.playground.data.locale.YahooSportsLocale;
import com.backyard.playground.exception.NotFoundException;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.observation.ObservationRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.http.HttpClient;
import java.util.Arrays;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Wires all outbound HTTP client beans.
 *
 * <p>
 * Each client is built in two layers:
 *
 * <ol>
 * <li>A Spring {@link HttpServiceProxyFactory} proxy that maps
 * {@code @HttpExchange} interface methods to HTTP calls via {@link RestClient}.
 * <li>A Resilience4j proxy (via {@link #wrapWithResilience}) that adds circuit
 * breaker and retry around every method call transparently.
 * </ol>
 *
 * <p>
 * Services inject the original client interfaces — they are unaware that
 * resilience is applied.
 */
@Configuration
public class ClientConfig {

    private static final Logger log = LoggerFactory.getLogger(ClientConfig.class);

    private final CircuitBreakerRegistry cbRegistry;
    private final RetryRegistry retryRegistry;

    public ClientConfig(CircuitBreakerRegistry cbRegistry, RetryRegistry retryRegistry) {
        this.cbRegistry = cbRegistry;
        this.retryRegistry = retryRegistry;
    }

    // Shared JDK HTTP client — Version.HTTP_2 enables ALPN negotiation
    // on TLS: the JVM advertises h2 in the ClientHello and uses HTTP/2
    // if the server agrees, falling back to HTTP/1.1 automatically.
    @Bean
    public JdkClientHttpRequestFactory httpRequestFactory() {
        var jdkClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build();
        return new JdkClientHttpRequestFactory(jdkClient);
    }

    @Bean
    public AmbeePollenClient ambeePollenClient(
            JdkClientHttpRequestFactory httpRequestFactory,
            ObservationRegistry observationRegistry) {
        String clientName = "ambee";
        RestClient restClient = RestClient.builder()
                .observationRegistry(observationRegistry)
                .baseUrl("https://ambee-maps-backend.ambeedata.com")
                .requestFactory(httpRequestFactory)
                .defaultHeader("Referer", "https://maps.getambee.com/")
                .defaultHeader("Origin", "https://maps.getambee.com")
                .defaultHeader(
                        "User-Agent",
                        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36")
                .defaultHeader("Accept", "application/json, text/plain, */*")
                .defaultHeader("Accept-Language", "en-US,en;q=0.9,zh-CN;q=0.8,zh;q=0.7")
                .defaultHeader(
                        "sec-ch-ua",
                        "\"Chromium\";v=\"146\", \"Not-A.Brand\";v=\"24\", \"Google Chrome\";v=\"146\"")
                .defaultHeader("sec-ch-ua-mobile", "?0")
                .defaultHeader("sec-ch-ua-platform", "\"macOS\"")
                .defaultHeader("sec-fetch-dest", "empty")
                .defaultHeader("sec-fetch-mode", "cors")
                .defaultHeader("sec-fetch-site", "cross-site")
                .requestInterceptor(LoggingInterceptor.noRedaction(clientName))
                .defaultStatusHandler(
                        HttpStatusCode::isError, ClientStatusHandlers::handleError)
                .build();

        AmbeePollenClient httpProxy = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(AmbeePollenClient.class);

        return wrapWithResilience(AmbeePollenClient.class, httpProxy, clientName);
    }

    @Bean
    public YahooSportsClient yahoosportsSportsClient(
            JdkClientHttpRequestFactory httpRequestFactory,
            ObservationRegistry observationRegistry) {
        String clientName = "yahoo_sports";
        RestClient restClient = RestClient.builder()
                .observationRegistry(observationRegistry)
                .baseUrl("https://mrest.sports.yahoo.com")
                .requestFactory(httpRequestFactory)
                .defaultHeader(
                        "User-Agent",
                        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36")
                .defaultHeader("Accept", "application/json, text/plain, */*")
                // Accept-Language is set dynamically per request by LocaleInterceptor,
                // which reads the resolved locale from ClientLocaleContextHolder and
                // maps it to a Yahoo-supported tag via YahooSportsLocale.
                .requestInterceptor(
                        new LocaleInterceptor(YahooSportsLocale::fromServiceLocale))
                .requestInterceptor(LoggingInterceptor.noRedaction(clientName))
                .defaultStatusHandler(
                        HttpStatusCode::isError, ClientStatusHandlers::handleError)
                .build();

        YahooSportsClient httpProxy = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(YahooSportsClient.class);

        // Yahoo returns 404 for unknown games — translate to null so
        // callers can filter rather than handle an exception.
        YahooSportsClient httpProxyWith404Handling = wrapWithErrorHandling(
                YahooSportsClient.class,
                httpProxy,
                (method, args, cause) -> {
                    if (cause instanceof NotFoundException) {
                        log.warn(
                                "Yahoo 404 on {}({})",
                                method.getName(),
                                Arrays.toString(args),
                                cause.getCause());
                        return null;
                    }
                    throw cause;
                });

        return wrapWithResilience(YahooSportsClient.class, httpProxyWith404Handling, clientName);
    }

    @Bean
    public GooglePollenClient googlePollenClient(
            JdkClientHttpRequestFactory httpRequestFactory,
            ObservationRegistry observationRegistry,
            Environment environment,
            @Value("${gcp.project-id:}") String gcpProjectId) {
        String clientName = "google_pollen";
        RestClient restClient = RestClient.builder()
                .observationRegistry(observationRegistry)
                .baseUrl("https://pollen.googleapis.com")
                .requestFactory(httpRequestFactory)
                .requestInterceptor(
                        QueryParamInterceptor.onlyApiKey(
                                "key",
                                "google_maps_api_key",
                                environment,
                                "../../secrets/api_keys.json",
                                gcpProjectId))
                .requestInterceptor(
                        LoggingInterceptor.withRedactedParams(clientName, Set.of("key")))
                .defaultStatusHandler(
                        HttpStatusCode::isError, ClientStatusHandlers::handleError)
                .build();

        GooglePollenClient httpProxy = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(GooglePollenClient.class);

        return wrapWithResilience(GooglePollenClient.class, httpProxy, clientName);
    }

    /**
     * Wraps {@code client} in a JDK dynamic proxy that applies a named Resilience4j
     * circuit breaker and retry to every method call on {@code iface}.
     *
     * <h3>Why a dynamic proxy?</h3>
     *
     * <p>
     * The clients are Spring {@code @HttpExchange} interfaces — there is no
     * concrete class to subclass or annotate. A JDK dynamic proxy lets us intercept
     * every method call at runtime without writing per-method delegation code.
     * Adding a method to the client interface automatically gets circuit breaker +
     * retry protection with no changes here.
     *
     * <h3>Decorator order</h3>
     *
     * <p>
     * Retry is applied first (innermost), then circuit breaker (outermost). This
     * means the breaker sees one outcome per retry group — individual retry
     * attempts are invisible to it. Only when all retries are exhausted does the
     * breaker record a failure. This prevents transient blips from tripping the
     * breaker prematurely.
     *
     * <h3>Exception unwrapping</h3>
     *
     * <p>
     * {@link java.lang.reflect.Method#invoke} wraps any exception thrown by the
     * target in an {@link InvocationTargetException}. We unwrap it before handing
     * to Resilience4j so that the retry and circuit breaker see the real exception
     * (e.g. {@link org.springframework.web.client.RestClientException}) and can
     * apply their exception predicate rules correctly.
     *
     * <h3>Naming</h3>
     *
     * <p>
     * {@code name} is the base client name (e.g. {@code "yahoosports"}). The
     * circuit breaker instance is keyed as {@code "<name>-<methodName>"} (e.g.
     * {@code "yahoosports-getTeamGames"}) so each endpoint has independent breaker
     * state — one endpoint failing does not trip the breaker for other endpoints on
     * the same client.
     *
     * <p>
     * The retry instance uses just {@code name}, shared across all methods on the
     * client. Retry state is per-call (not shared), so this only affects retry
     * configuration — all methods on a client share the same max-attempts and
     * backoff settings.
     *
     * <h3>Configuration</h3>
     *
     * <p>
     * Add entries to {@code application.properties} for each endpoint and the
     * shared retry key. Example for a client named {@code "yahoosports"} with
     * methods {@code getTeamGames} and {@code
     * getGameDetails}:
     *
     * <pre>
     *
     * # circuit breaker — one instance per endpoint
     * resilience4j.circuitbreaker.instances.yahoosports-getTeamGames.minimum-number-of-calls=5
     * resilience4j.circuitbreaker.instances.yahoosports-getTeamGames.sliding-window-size=10
     * resilience4j.circuitbreaker.instances.yahoosports-getTeamGames.wait-duration-in-open-state=30s
     * resilience4j.circuitbreaker.instances.yahoosports-getGameDetails.minimum-number-of-calls=5
     * resilience4j.circuitbreaker.instances.yahoosports-getGameDetails.sliding-window-size=10
     * resilience4j.circuitbreaker.instances.yahoosports-getGameDetails.wait-duration-in-open-state=30s
     *
     * # retry — one instance per client (config only, not state)
     * resilience4j.retry.instances.yahoosports.max-attempts=3
     * resilience4j.retry.instances.yahoosports.wait-duration=500ms
     * resilience4j.retry.instances.yahoosports.enable-exponential-backoff=true
     * resilience4j.retry.instances.yahoosports.exponential-backoff-multiplier=2
     * resilience4j.retry.instances.yahoosports.randomized-wait-factor=0.5
     * </pre>
     *
     * @param iface  the client interface to proxy
     * @param client the raw client to delegate to
     * @param name   base name — circuit breaker instances are keyed as
     *               {@code "<name>-<methodName>"}; retry instance is keyed as
     *               {@code "<name>"}
     */
    @SuppressWarnings("unchecked")
    private <T> T wrapWithResilience(Class<T> iface, T client, String name) {
        return (T) Proxy.newProxyInstance(
                iface.getClassLoader(),
                new Class<?>[] { iface },
                (proxy, method, args) -> {
                    String cbName = name + "-" + method.getName();
                    Supplier<Object> call = () -> {
                        try {
                            return method.invoke(client, args);
                        } catch (InvocationTargetException e) {
                            Throwable cause = e.getCause();
                            if (cause instanceof RuntimeException re)
                                throw re;
                            throw new RuntimeException(cause);
                        } catch (IllegalAccessException e) {
                            throw new RuntimeException(e);
                        }
                    };
                    Supplier<Object> withRetry = retryRegistry.retry(name).decorateSupplier(call);
                    return cbRegistry
                            .circuitBreaker(cbName)
                            .decorateSupplier(withRetry)
                            .get();
                });
    }

    /**
     * Wraps {@code client} in a JDK dynamic proxy that intercepts every method call
     * and routes exceptions through {@code onError}.
     *
     * <p>
     * Use this to translate client-specific error conditions into return values
     * before resilience logic sees them — for example, converting a 404
     * {@link NotFoundException} to {@code null} so callers can filter instead of
     * catching. Because this proxy sits inside {@link #wrapWithResilience}, the
     * translated outcome (null or a value) is seen by Resilience4j as a success and
     * will not trigger retries or circuit breaker failure recording.
     *
     * <p>
     * Typical call order:
     *
     * <pre>
     * T inner = wrapWithErrorHandling(iface, httpProxy, handler);
     * return wrapWithResilience(iface, inner, name);
     * </pre>
     *
     * @param iface   the client interface to proxy
     * @param client  the raw HTTP client to delegate to
     * @param onError called with the unwrapped cause whenever an exception is
     *                thrown — may return a fallback value or rethrow
     */
    @SuppressWarnings("unchecked")
    private <T> T wrapWithErrorHandling(Class<T> iface, T client, ErrorHandler onError) {
        return (T) Proxy.newProxyInstance(
                iface.getClassLoader(),
                new Class<?>[] { iface },
                (proxy, method, args) -> {
                    try {
                        return method.invoke(client, args);
                    } catch (InvocationTargetException e) {
                        return onError.handle(method, args, e.getCause());
                    }
                });
    }

    /**
     * Handles an exception thrown during a proxied method call. May return a
     * fallback value or rethrow the cause.
     */
    @FunctionalInterface
    interface ErrorHandler {
        Object handle(java.lang.reflect.Method method, Object[] args, Throwable cause)
                throws Throwable;
    }
}
