package com.backyard.playground.service.notification;

import java.math.BigInteger;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.backyard.notification.common.kafka.Topics;
import com.backyard.notification.common.kafka.message.FanoutMessage;
import com.backyard.playground.data.model.notification.TriggerFanoutEventInputDTO;
import com.backyard.playground.data.model.notification.TriggerFanoutEventResultDTO;
import com.backyard.playground.exception.BadRequestException;

@Service
public class NotificationEventService {

    private static final int FANOUT_RANGE_COUNT = 20;
    private static final int FANOUT_PARTITION_BUCKETS = 16;

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public NotificationEventService(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /** Publishes one fan-out message per Cassandra token range segment. */
    public TriggerFanoutEventResultDTO publishFanoutEvent(TriggerFanoutEventInputDTO req) {
        validate(req);

        UUID eventId = UUID.randomUUID();
        for (int i = 0; i < FANOUT_RANGE_COUNT; i++) {
            FanoutMessage msg = new FanoutMessage();
            msg.setTopicId(req.topicId().trim());
            msg.setEventId(eventId);
            msg.setTitle(req.title().trim());
            msg.setBody(req.body());
            msg.setData(Map.of());
            msg.setRangeStart(rangeStart(i, FANOUT_RANGE_COUNT));
            msg.setRangeEnd(rangeEnd(i, FANOUT_RANGE_COUNT));

            String key = Integer.toString(i % FANOUT_PARTITION_BUCKETS);
            kafkaTemplate.send(Topics.NOTIFICATION_FANOUT, key, msg);
        }

        return new TriggerFanoutEventResultDTO(eventId, req.topicId().trim(), FANOUT_RANGE_COUNT);
    }

    private static void validate(TriggerFanoutEventInputDTO req) {
        if (req == null) {
            throw new BadRequestException("request body is required");
        }
        if (StringUtils.isBlank(req.topicId())) {
            throw new BadRequestException("topicId is required");
        }
        if (StringUtils.isBlank(req.title())) {
            throw new BadRequestException("title is required");
        }
    }

    private static long rangeStart(int rangeIndex, int rangeCount) {
        BigInteger min = BigInteger.valueOf(Long.MIN_VALUE);
        BigInteger total = BigInteger.ONE.shiftLeft(64); // 2^64 token space
        return min
                .add(total.multiply(BigInteger.valueOf(rangeIndex))
                        .divide(BigInteger.valueOf(rangeCount)))
                .longValue();
    }

    private static long rangeEnd(int rangeIndex, int rangeCount) {
        if (rangeIndex == rangeCount - 1) {
            // End is exclusive in Cassandra query; cap the final segment at max long.
            return Long.MAX_VALUE;
        }
        BigInteger min = BigInteger.valueOf(Long.MIN_VALUE);
        BigInteger total = BigInteger.ONE.shiftLeft(64); // 2^64 token space
        return min
                .add(total.multiply(BigInteger.valueOf(rangeIndex + 1L))
                        .divide(BigInteger.valueOf(rangeCount)))
                .longValue();
    }
}
