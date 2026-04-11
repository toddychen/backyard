package com.backyard.notification.consumer.service;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.springframework.stereotype.Service;

import com.backyard.notification.common.kafka.message.PushDestinationInfo;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;

import jakarta.annotation.PostConstruct;

/**
 * Cassandra read operations for the notification pipeline.
 *
 * <p>
 * Handles subscription fan-out reads (by topic_id + token range) and push
 * destination reads (by user_id). Uses the driver session directly for queries
 * that Spring Data Cassandra derived methods cannot express (token() range
 * predicates, async batch reads).
 */
@Service
public class NotificationDataService {

    private final CqlSession session;
    private PreparedStatement selectSubscribers;
    private PreparedStatement selectDestinations;

    public NotificationDataService(CqlSession session) {
        this.session = session;
    }

    @PostConstruct
    void prepare() {
        selectSubscribers = session.prepare("""
                SELECT user_id FROM subscriptions
                WHERE topic_id         = :topicId
                  AND token(user_id) >= :rangeStart
                  AND token(user_id) <  :rangeEnd
                """);

        selectDestinations = session.prepare("""
                SELECT user_id, push_token, platform, bundle_id, apns_env
                FROM push_destinations_by_user
                WHERE user_id = ?
                  AND token_valid = true
                  AND notifications_enabled = true
                ALLOW FILTERING
                """);
        // ALLOW FILTERING is safe here — user_id is the partition key so
        // this always reads a single partition (~2 rows). The filter on
        // token_valid and notifications_enabled scans only those rows,
        // never the whole table.
    }

    /**
     * Returns an {@link Iterable} of user_ids for all active subscribers to
     * {@code topicId} within the given Murmur3 token range.
     *
     * <p>
     * The driver fetches result pages lazily — this method does not load all rows
     * into memory at once. The caller (FanOutConsumer) drains the iterable and
     * batches into groups of 1000 before publishing to notification.resolve.
     */
    public Iterable<UUID> streamSubscribers(String topicId, long rangeStart, long rangeEnd) {
        var bound = selectSubscribers.bind()
                .setString("topicId", topicId)
                .setLong("rangeStart", rangeStart)
                .setLong("rangeEnd", rangeEnd);

        var resultSet = session.execute(bound);

        return () -> new Iterator<>() {
            private final Iterator<Row> rows = resultSet.iterator();

            @Override
            public boolean hasNext() {
                return rows.hasNext();
            }

            @Override
            public UUID next() {
                return rows.next().getUuid("user_id");
            }
        };
    }

    /**
     * Resolves deliverable push destinations for a batch of user_ids.
     *
     * <p>
     * Fires one async Cassandra read per user_id — all requests in-flight
     * simultaneously over the driver's existing TCP connections using stream IDs.
     * Returns only rows where token_valid=true and notifications_enabled=true
     * (filtered server-side within each single-partition read).
     *
     * <p>
     * Expected latency for 1000 concurrent reads: 5–80ms depending on cache warmth.
     * Do NOT use WHERE user_id IN (...) — that serializes reads on the coordinator
     * and is slower than concurrent async reads.
     */
    public List<PushDestinationInfo> resolveDestinations(List<UUID> userIds) {
        var futures = userIds.stream()
                .map(userId -> session
                        .executeAsync(selectDestinations.bind(userId))
                        .toCompletableFuture()
                        .thenApply(rs -> {
                            var infos = new ArrayList<PushDestinationInfo>();
                            for (Row row : rs.currentPage()) {
                                infos.add(toDestinationInfo(row));
                            }
                            return infos;
                        }))
                .toList();

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> futures.stream()
                        .flatMap(f -> f.join().stream())
                        .toList())
                .join();
    }

    private PushDestinationInfo toDestinationInfo(Row row) {
        var info = new PushDestinationInfo();
        info.setUserId(row.getUuid("user_id"));
        info.setPushToken(row.getString("push_token"));
        info.setPlatform(row.getString("platform"));
        info.setBundleId(row.getString("bundle_id"));
        info.setApnsEnv(row.getString("apns_env"));
        return info;
    }
}
