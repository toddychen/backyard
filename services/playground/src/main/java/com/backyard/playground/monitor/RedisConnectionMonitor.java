package com.backyard.playground.monitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Monitors Redis connectivity with periodic pings. Resets the Lettuce connection after {@link #FAILURE_THRESHOLD}
 * consecutive failures, allowing a fresh connection on the next cache access.
 *
 * <p>
 * Since Redis is a cache layer, failures degrade to origin lookups rather than errors — the reset is a recovery aid,
 * not a circuit breaker.
 */
@Component
@Profile("!home")
public class RedisConnectionMonitor {

    private static final Logger log = LoggerFactory.getLogger(RedisConnectionMonitor.class);

    private static final int FAILURE_THRESHOLD = 3;

    private final RedisTemplate<String, Object> redisTemplate;
    private final RedisConnectionFactory connectionFactory;

    private int consecutiveFailures = 0;

    public RedisConnectionMonitor(
            RedisTemplate<String, Object> redisTemplate,
            RedisConnectionFactory connectionFactory) {
        this.redisTemplate = redisTemplate;
        this.connectionFactory = connectionFactory;
    }

    @Scheduled(fixedDelay = 30_000)
    public void check() {
        try {
            redisTemplate.execute((RedisCallback<String>) conn -> conn.ping());
            if (consecutiveFailures > 0) {
                log.info("Redis connection recovered");
            }
            consecutiveFailures = 0;
        } catch (Exception e) {
            consecutiveFailures++;
            log.warn("Redis ping failed ({}/{}): {}",
                    consecutiveFailures, FAILURE_THRESHOLD,
                    e.getMessage());
            if (consecutiveFailures >= FAILURE_THRESHOLD) {
                resetConnection();
                consecutiveFailures = 0;
            }
        }
    }

    private void resetConnection() {
        log.warn("Resetting Redis connection after {} consecutive failures", FAILURE_THRESHOLD);
        if (connectionFactory instanceof LettuceConnectionFactory lcf) {
            lcf.resetConnection();
        }
    }
}
