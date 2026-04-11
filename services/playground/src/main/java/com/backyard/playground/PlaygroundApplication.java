package com.backyard.playground;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.backyard.notification.common.kafka.KafkaTopicConfig;

@SpringBootApplication
@EnableScheduling
@Import(KafkaTopicConfig.class)
public class PlaygroundApplication {
    public static void main(String[] args) {
        SpringApplication.run(PlaygroundApplication.class, args);
    }
}
