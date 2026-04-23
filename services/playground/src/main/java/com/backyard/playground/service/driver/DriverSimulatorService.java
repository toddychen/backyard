package com.backyard.playground.service.driver;

import java.util.Random;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.integration.leader.event.OnGrantedEvent;
import org.springframework.integration.leader.event.OnRevokedEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Profile("!home")
public class DriverSimulatorService {

    private static final Logger log = LoggerFactory.getLogger(DriverSimulatorService.class);
    private static final int DRIVER_COUNT = 2_000;
    private static final String ENABLED_KEY = "simulator:enabled";

    private final S2Service s2Service;
    private final DriverRedisRepository repository;
    private final StringRedisTemplate redis;

    private volatile boolean leader = false;
    private volatile Driver[] drivers;
    private volatile Random rng;

    public DriverSimulatorService(
            S2Service s2Service,
            DriverRedisRepository repository,
            StringRedisTemplate redis) {
        this.s2Service = s2Service;
        this.repository = repository;
        this.redis = redis;
    }

    public void start() {
        redis.opsForValue().set(ENABLED_KEY, "1");
        log.info("Driver simulator enabled");
    }

    public void stop() {
        redis.delete(ENABLED_KEY);
        drivers = null;
        log.info("Driver simulator disabled");
    }

    public boolean isRunning() {
        return Boolean.TRUE.equals(redis.hasKey(ENABLED_KEY));
    }

    @EventListener
    public void onGranted(OnGrantedEvent event) {
        leader = true;
        log.info("This pod became simulator leader");
    }

    @EventListener
    public void onRevoked(OnRevokedEvent event) {
        leader = false;
        drivers = null;
        log.info("This pod lost simulator leadership");
    }

    @Scheduled(fixedDelay = 1000)
    public void tick() {
        if (!leader || !isRunning())
            return;

        if (drivers == null) {
            rng = new Random();
            drivers = repository.loadAll(rng);
            if (drivers.length == 0) {
                drivers = initFresh();
            }
            log.info("Simulator initialized with {} drivers", drivers.length);
        }

        for (Driver d : drivers) {
            d.move(rng);
        }
        repository.updateAll(drivers, s2Service);
    }

    private Driver[] initFresh() {
        Driver[] d = new Driver[DRIVER_COUNT];
        for (int i = 0; i < DRIVER_COUNT; i++) {
            d[i] = new Driver(UUID.randomUUID(), rng);
        }
        return d;
    }
}
