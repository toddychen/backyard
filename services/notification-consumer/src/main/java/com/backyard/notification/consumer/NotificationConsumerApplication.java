package com.backyard.notification.consumer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

import com.backyard.notification.common.kafka.KafkaTopicConfig;

@SpringBootApplication
@Import(KafkaTopicConfig.class)
public class NotificationConsumerApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationConsumerApplication.class, args);
    }
}
