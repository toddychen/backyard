package com.backyard.playground.service;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import com.backyard.playground.cache.CacheConfig;
import com.backyard.playground.client.YahooSportsClient;
import com.backyard.playground.data.api.yahoo.YahooGame;
import com.backyard.playground.data.sport.game.Game;

@Service
public class SportService {
    private static final Logger log = LoggerFactory.getLogger(SportService.class);

    private final YahooSportsClient yahooSportsClient;
    private final ContextSnapshotFactory contextSnapshotFactory = ContextSnapshotFactory.builder().build();

    public SportService(YahooSportsClient yahooSportsClient) {
        this.yahooSportsClient = yahooSportsClient;
    }

    public List<Game> getTeamGames(String teamId, int nextX, int lastX) {
        List<YahooGame> games = yahooSportsClient.getTeamGames(teamId, nextX, lastX);
        if (games == null)
            return List.of();
        return games.stream()
                .map(g -> yahooSportsClient.getGameDetails(g.gameId()))
                .filter(Objects::nonNull)
                .map(Game::from)
                .toList();
    }

    public List<Game> getTeamGamesParallel(String teamId, int nextX, int lastX) {
        List<YahooGame> games = yahooSportsClient.getTeamGames(teamId, nextX, lastX);
        if (games == null)
            return List.of();
        try (ExecutorService executor = ContextExecutorService.wrap(
                Executors.newVirtualThreadPerTaskExecutor(),
                contextSnapshotFactory::captureAll)) {
            List<Future<YahooGame>> futures = games.stream()
                    .map(g -> executor.submit(() -> yahooSportsClient.getGameDetails(g.gameId())))
                    .toList();
            return futures.stream()
                    .map(f -> {
                        try {
                            return f.get();
                        } catch (Exception e) {
                            log.warn("failed to fetch game details", e);
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .map(Game::from)
                    .toList();
        }
    }

    // unless: skip caching null results (e.g. game not found) — avoids
    // storing a transient absence that could mask a later valid response
    @Cacheable(value = CacheConfig.CACHE_GAME_DETAILS, key = "#gameId", unless = "#result == null")
    public Game getGameDetails(String gameId) {
        YahooGame game = yahooSportsClient.getGameDetails(gameId);
        return game == null ? null : Game.from(game);
    }
}
