package com.backyard.playground.service.driver;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.integration.redis.util.RedisLockRegistry;
import org.springframework.integration.support.leader.LockRegistryLeaderInitiator;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@Profile("!home")
public class DriverSimulatorConfig {

    @Bean(name = "driverSimulatorScheduler", destroyMethod = "shutdown")
    public ScheduledExecutorService driverSimulatorScheduler() {
        return Executors.newScheduledThreadPool(100, Thread.ofVirtual().factory());
    }

    @Bean(destroyMethod = "destroy")
    public RedisLockRegistry driverLockRegistry(RedisConnectionFactory connectionFactory) {
        return new RedisLockRegistry(connectionFactory, "driver-simulator");
    }

    @Bean(destroyMethod = "stop")
    public LockRegistryLeaderInitiator driverLeaderInitiator(RedisLockRegistry driverLockRegistry) {
        return new LockRegistryLeaderInitiator(driverLockRegistry);
    }
}
