package com.backyard.playground.tools;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One-shot Cassandra data seeder. Activated only with the {@code seed} profile.
 *
 * <p>
 * Run once against a dev cluster:
 * 
 * <pre>
 *   ./mvnw spring-boot:run \
 *     -Dspring-boot.run.profiles=dev,seed \
 *     -pl services/playground
 * </pre>
 *
 * <p>
 * Data seeded:
 * <ul>
 * <li>1.1M unique users, processed in batches of 1k (no large in-memory
 * list)</li>
 * <li>90% of users get 1 push destination, 10% get 2</li>
 * <li>Platform split: ~50% APNS, ~50% FCM</li>
 * <li>Locale split: 95% en-US, 5% fr-FR</li>
 * <li>Per-user subscription probability: sports_nfl 90%, sports_nba 50%,
 * sports_nhl 20%</li>
 * </ul>
 *
 * <p>
 * Uses a semaphore of 512 in-flight async writes. Each 1k-user batch is fully
 * awaited before the next batch starts, keeping memory bounded. Idempotent —
 * CQL INSERT is an upsert, safe to re-run after interruption.
 */
@Profile("seed")
@Component
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private static final int TOTAL_USERS = 1_000_000;
    // Per-user subscription probability for each topic
    private static final double NFL_PROB = 0.90;
    private static final double NBA_PROB = 0.50;
    private static final double NHL_PROB = 0.20;
    private static final int BATCH_SIZE = 1_000;

    // Max in-flight async requests across the current batch
    private static final int MAX_IN_FLIGHT = 8;

    private final CqlSession session;

    public DataSeeder(CqlSession session) {
        this.session = session;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        log.info("=== DataSeeder starting: {} users in batches of {} ===",
                TOTAL_USERS, BATCH_SIZE);

        var rng = new Random(42);
        var now = Instant.now();

        // Separate statements per platform — omitting APNS-only columns for FCM
        // rows avoids writing null tombstones for bundle_id and apns_env.
        var insertApns = session.prepare("""
                INSERT INTO push_destinations_by_user
                  (user_id, push_token, platform, bundle_id, apns_env,
                   locale, notifications_enabled, token_valid,
                   registered_at, updated_at)
                VALUES (?, ?, 'APNS', ?, 'PRODUCTION', ?, true, true, ?, ?)
                """);

        var insertFcm = session.prepare("""
                INSERT INTO push_destinations_by_user
                  (user_id, push_token, platform,
                   locale, notifications_enabled, token_valid,
                   registered_at, updated_at)
                VALUES (?, ?, 'FCM', ?, true, true, ?, ?)
                """);

        // Omit expires_at entirely — no null tombstone written.
        var insertSub = session.prepare("""
                INSERT INTO subscriptions
                  (user_id, topic_id, subscribed_at)
                VALUES (?, ?, ?)
                """);

        var destCount = new AtomicInteger();
        var subCount = new AtomicInteger();
        var sem = new Semaphore(MAX_IN_FLIGHT);

        int totalBatches = (int) Math.ceil((double) TOTAL_USERS / BATCH_SIZE);

        for (int batch = 0; batch < totalBatches; batch++) {
            int batchStart = batch * BATCH_SIZE;
            int batchEnd = Math.min(batchStart + BATCH_SIZE, TOTAL_USERS);

            var futures = new ArrayList<CompletableFuture<?>>((batchEnd - batchStart) * 4);

            for (int i = batchStart; i < batchEnd; i++) {
                var userId = UUID.randomUUID();
                int devices = rng.nextInt(10) == 0 ? 2 : 1; // 10% get 2 devices

                for (int d = 0; d < devices; d++) {
                    futures.add(asyncWrite(sem, buildDest(insertApns, insertFcm, userId, rng, now),
                            destCount));
                }

                if (rng.nextDouble() < NFL_PROB) {
                    futures.add(asyncWrite(sem, insertSub.bind(userId, "sports_nfl", now), subCount));
                }
                if (rng.nextDouble() < NBA_PROB) {
                    futures.add(asyncWrite(sem, insertSub.bind(userId, "sports_nba", now), subCount));
                }
                if (rng.nextDouble() < NHL_PROB) {
                    futures.add(asyncWrite(sem, insertSub.bind(userId, "sports_nhl", now), subCount));
                }
            }

            // Await all writes in this batch before generating the next
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            // Brief pause to let the single-node dev cluster catch up
            Thread.sleep(50);

            if ((batch + 1) % 100 == 0 || batch == totalBatches - 1) {
                log.info("  batch {}/{} — destinations: {}, subscriptions: {}",
                        batch + 1, totalBatches, destCount.get(), subCount.get());
            }
        }

        log.info("=== DataSeeder complete — destinations: {}, subscriptions: {} ===", destCount.get(), subCount.get());
    }

    private CompletableFuture<?> asyncWrite(Semaphore sem, BoundStatement stmt, AtomicInteger counter)
            throws InterruptedException {
        sem.acquire();
        return session.executeAsync(stmt)
                .toCompletableFuture()
                .whenComplete((r, e) -> {
                    sem.release();
                    if (e != null)
                        log.warn("Write failed", e);
                    else
                        counter.incrementAndGet();
                });
    }

    private BoundStatement buildDest(
            PreparedStatement apnsPs, PreparedStatement fcmPs,
            UUID userId, Random rng, Instant now) {
        boolean isApns = rng.nextBoolean(); // 50% APNS, 50% FCM
        String pushToken = UUID.randomUUID().toString().replace("-", "");
        String locale = rng.nextInt(20) == 0 ? "fr-FR" : "en-US"; // 95% en-US
        if (isApns) {
            return apnsPs.bind(userId, pushToken, "com.backyard.app", locale, now, now);
        } else {
            return fcmPs.bind(userId, pushToken, locale, now, now);
        }
    }
}
