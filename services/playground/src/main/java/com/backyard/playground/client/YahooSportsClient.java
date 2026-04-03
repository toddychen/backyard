package com.backyard.playground.client;

import com.backyard.playground.data.api.yahoo.YahooGame;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

import java.util.List;

/**
 * HTTP client for the Yahoo Sports REST API.
 *
 * <h3>Resilience</h3>
 *
 * <p>
 * Resilience4j circuit breaker and retry are applied transparently by {@code
 * ClientConfig.wrapWithResilience}. Each method gets its own circuit breaker
 * instance keyed as {@code "yahoosports-<methodName>"}, so a failing endpoint
 * does not trip the breaker for other endpoints. Retry config is shared under
 * the {@code "yahoosports"} key (retry state is per-call, so methods remain
 * independent at runtime).
 *
 * <h3>Configuration</h3>
 *
 * <p>
 * Circuit breaker and retry are configured in {@code application.properties}:
 *
 * <pre>
 * # per-endpoint circuit breaker
 * resilience4j.circuitbreaker.instances
 *     .yahoosports-getTeamGames.minimum-number-of-calls=5
 * resilience4j.circuitbreaker.instances
 *     .yahoosports-getGameDetails.minimum-number-of-calls=5
 *
 * # shared retry config (per-call, not shared state)
 * resilience4j.retry.instances.yahoosports.max-attempts=3
 * resilience4j.retry.instances.yahoosports.wait-duration=500ms
 * </pre>
 *
 * <h3>404 handling</h3>
 *
 * <p>
 * Yahoo returns 404 for unknown games. {@code ClientConfig} translates these to
 * {@code null} before resilience logic runs, so 404s are not retried and do not
 * count as circuit breaker failures.
 */
@HttpExchange("/api/v8")
public interface YahooSportsClient {

    @GetExchange("/team/{teamId}/games")
    List<YahooGame> getTeamGames(
            @PathVariable("teamId") String teamId,
            @RequestParam("next_x") int nextX,
            @RequestParam("last_x") int lastX);

    @GetExchange("/game/{gameId}/details")
    YahooGame getGameDetails(@PathVariable("gameId") String gameId);
}
