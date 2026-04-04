package com.backyard.playground.service;
import org.springframework.context.annotation.Profile;

import com.backyard.playground.data.persist.mysql.auth.RefreshTokenRepository;

import jakarta.transaction.Transactional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Periodically purges expired refresh tokens from the DB. Safe to delete once
 * expired — an expired token is rejected regardless, so its revoked history is
 * no longer useful.
 */
@Profile("!home")
@Service
public class RefreshTokenCleanupService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanupService.class);

    private final RefreshTokenRepository refreshTokenRepository;

    public RefreshTokenCleanupService(RefreshTokenRepository refreshTokenRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
    }

    /** Runs daily at 03:00 UTC. */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void purgeExpiredTokens() {
        refreshTokenRepository.deleteExpiredBefore(Instant.now());
        log.info("purged expired refresh tokens");
    }
}
