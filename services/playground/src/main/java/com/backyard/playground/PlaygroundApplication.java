package com.backyard.playground;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.data.cassandra.autoconfigure.DataCassandraReactiveRepositoriesAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.backyard.notification.common.kafka.KafkaTopicConfig;

@SpringBootApplication(exclude = {
        // We use blocking Cassandra only — no reactive (Mono/Flux) repos
        DataCassandraReactiveRepositoriesAutoConfiguration.class,
        // We use StringRedisTemplate directly — no @RedisHash repos
        DataRedisRepositoriesAutoConfiguration.class,
        // We use JWT — no username/password UserDetailsService needed
        UserDetailsServiceAutoConfiguration.class
})
@EnableScheduling
@Import(KafkaTopicConfig.class)
public class PlaygroundApplication {
    public static void main(String[] args) {
        SpringApplication.run(PlaygroundApplication.class, args);
    }
}
