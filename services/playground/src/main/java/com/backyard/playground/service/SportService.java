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
import org.springframework.stereotype.Service;

import com.backyard.playground.client.YahooSportsClient;
import com.backyard.playground.data.api.yahoo.YahooGame;
import com.backyard.playground.data.sport.game.Game;

@Service
public class SportService {
    private static final Logger log = LoggerFactory.getLogger(SportService.class);

    private final YahooSportsClient yahooSportsClient;
    private final GameService gameService;
    private final ContextSnapshotFactory contextSnapshotFactory = ContextSnapshotFactory.builder().build();

    public SportService(YahooSportsClient yahooSportsClient, GameService gameService) {
        this.yahooSportsClient = yahooSportsClient;
        this.gameService = gameService;
    }

    public List<Game> getTeamGames(String teamId, int nextX, int lastX, boolean cached) {
        List<YahooGame> games = yahooSportsClient.getTeamGames(teamId, nextX, lastX);
        if (games == null)
            return List.of();
        return games.stream()
                .map(g -> cached ? gameService.getGameDetails(g.gameId())
                                 : gameService.fetchGameDetails(g.gameId()))
                .filter(Objects::nonNull)
                .toList();
    }

    public List<Game> getTeamGamesParallel(String teamId, int nextX, int lastX, boolean cached) {
        List<YahooGame> games = yahooSportsClient.getTeamGames(teamId, nextX, lastX);
        if (games == null)
            return List.of();
        try (ExecutorService executor = ContextExecutorService.wrap(
                Executors.newVirtualThreadPerTaskExecutor(),
                contextSnapshotFactory::captureAll)) {
            List<Future<Game>> futures = games.stream()
                    .map(g -> executor.submit(() ->
                            cached ? gameService.getGameDetails(g.gameId())
                                   : gameService.fetchGameDetails(g.gameId())))
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
                    .toList();
        }
    }
}
