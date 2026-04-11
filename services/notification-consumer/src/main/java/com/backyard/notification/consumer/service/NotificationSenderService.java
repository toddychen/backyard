package com.backyard.notification.consumer.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.backyard.notification.common.kafka.message.PushDestinationInfo;
import com.backyard.notification.common.kafka.message.RetryMessage;
import com.backyard.notification.common.kafka.message.SendMessage;
import com.backyard.notification.consumer.kafka.producer.RetryProducer;

/**
 * Performs dry-run push sends and owns all error-handling decisions.
 *
 * <p>
 * APNS sends one HTTP/2 call per token — handled per-destination. FCM sends up
 * to 500 tokens in one batch call — handled per-message.
 *
 * <p>
 * Error handling:
 * <ul>
 * <li>Token invalid — log warning, no retry (TODO: mark token_valid=false)</li>
 * <li>APNS transient failure — one {@code RetryMessage} for that token</li>
 * <li>FCM batch transient failure — one {@code RetryMessage} per token in the
 * batch (each retries independently)</li>
 * <li>Failure inside {@code RetryConsumer} — forward to DLQ, no second
 * retry</li>
 * </ul>
 */
@Service
public class NotificationSenderService {

    private static final Logger log = LoggerFactory.getLogger(NotificationSenderService.class);
    private static final long RETRY_DELAY_MS = 5_000;

    private final RetryProducer retryProducer;

    public NotificationSenderService(RetryProducer retryProducer) {
        this.retryProducer = retryProducer;
    }

    /**
     * Sends all APNS tokens in the message concurrently — one virtual thread per
     * token. All tokens are in-flight simultaneously; the caller blocks until the
     * last one completes. Each token's error is handled independently.
     */
    public void sendApnsWithRetry(SendMessage msg) {
        // newVirtualThreadPerTaskExecutor has no built-in concurrency limit — one
        // virtual
        // thread per token, all in-flight simultaneously. Batch size is capped at 200
        // upstream
        // (ResolveConsumer) so this is fine for now.
        //
        // TODO: add a Semaphore(200) when wiring in real pushy calls. The APNS HTTP/2
        // connection has a max-concurrent-streams limit (~500-1000); without a cap, all
        // 200 virtual threads hit the same connection at once. The semaphore acts as a
        // backpressure valve and also guards against an oversized batch from upstream.
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var dest : msg.getDestinations()) {
                executor.submit(() -> sendApnsOne(msg, dest));
            }
        } // close() waits for all submitted tasks to finish
    }

    private void sendApnsOne(SendMessage msg, PushDestinationInfo dest) {
        try {
            sendApns(dest.getPushToken(), dest.getBundleId(), dest.getApnsEnv(), msg.getTitle());
        } catch (TokenInvalidException e) {
            // TODO: mark token_valid=false in push_destinations_by_user
            log.warn("APNS: invalid token userId={}", dest.getUserId());
        } catch (Exception e) {
            log.warn("APNS: transient error, queued for retry. eventId={} userId={} token={}",
                    msg.getEventId(), dest.getUserId(), truncateToken(dest.getPushToken()));
            retryProducer.publish(toRetryMessage(msg, dest));
        }
    }

    /**
     * Sends all FCM tokens in one batch call. The real FCM batch API returns a
     * per-token result — failed tokens are retried individually. The dry-run
     * simulates this by randomly marking 0.1% of tokens as failed.
     */
    public void sendFcmBatchWithRetry(SendMessage msg) {
        var failed = sendFcmBatch(msg);
        if (!failed.isEmpty()) {
            log.warn("FCM: {}/{} tokens failed, queuing for retry. eventId={}",
                    failed.size(), msg.getDestinations().size(), msg.getEventId());
            for (var dest : failed) {
                retryProducer.publish(toRetryMessage(msg, dest));
            }
        }
    }

    /**
     * Attempts one send (APNS or FCM single-token). TokenInvalidException is
     * swallowed — no retry, no DLQ. Any other exception is rethrown so that
     * {@code @RetryableTopic} in RetryConsumer can route it to the DLT.
     */
    public void sendOrThrow(RetryMessage msg) {
        var dest = msg.getDestination();
        try {
            if ("APNS".equals(dest.getPlatform())) {
                sendApns(dest.getPushToken(), dest.getBundleId(), dest.getApnsEnv(), msg.getTitle());
            } else {
                sendFcmSingle(dest.getPushToken(), msg.getTitle());
            }
        } catch (TokenInvalidException e) {
            // TODO: mark token_valid=false in push_destinations_by_user
            log.warn("Retry: invalid token userId={} platform={}", dest.getUserId(), dest.getPlatform());
        }
        // Other exceptions propagate — @RetryableTopic routes them to
        // notification.retry-dlq
    }

    // --- dry-run senders ---

    private void sendApns(String pushToken, String bundleId, String apnsEnv, String title) {
        if (ThreadLocalRandom.current().nextDouble() < 0.001) {
            throw new RuntimeException("APNS simulated network failure");
        }
        // TODO: replace with pushy HTTP/2 call — one call per token
        log.info("APNS DRY-RUN | token={} bundle={} env={} title={}",
                pushToken, bundleId, apnsEnv, title);
    }

    // TODO: replace with Firebase Admin SDK BatchMessage call — up to 500 tokens
    // per call. The real API returns a BatchResponse with per-token SendResponse —
    // collect failures and return them; the caller retries each independently.
    private List<PushDestinationInfo> sendFcmBatch(SendMessage msg) {
        var failed = new ArrayList<PushDestinationInfo>();
        for (var dest : msg.getDestinations()) {
            if (ThreadLocalRandom.current().nextDouble() < 0.001) {
                failed.add(dest);
            } else {
                log.info("FCM DRY-RUN | token={} title={}", dest.getPushToken(), msg.getTitle());
            }
        }
        return failed;
    }

    private void sendFcmSingle(String pushToken, String title) {
        if (ThreadLocalRandom.current().nextDouble() < 0.001) {
            throw new RuntimeException("FCM simulated network failure");
        }
        // TODO: replace with Firebase Admin SDK single send (used in retry path)
        log.info("FCM DRY-RUN | token={} title={}", pushToken, title);
    }

    // --- helpers ---

    private RetryMessage toRetryMessage(SendMessage src, PushDestinationInfo dest) {
        var msg = new RetryMessage();
        msg.setEventId(src.getEventId());
        msg.setTitle(src.getTitle());
        msg.setBody(src.getBody());
        msg.setData(src.getData());
        msg.setDestination(dest);
        msg.setProcessAfter(Instant.now().plusMillis(RETRY_DELAY_MS));
        return msg;
    }

    private static String truncateToken(String token) {
        if (token == null || token.length() <= 8)
            return token;
        return token.substring(0, 4) + "..." + token.substring(token.length() - 4);
    }

    /** Thrown when APNS returns 410 or FCM returns UNREGISTERED. */
    public static class TokenInvalidException extends RuntimeException {
        public TokenInvalidException(String msg) {
            super(msg);
        }
    }
}
