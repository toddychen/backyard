package com.backyard.playground.service;

import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.backyard.playground.client.YahooSportsClient;
import com.backyard.playground.data.api.yahoo.YahooGame;
import com.backyard.playground.data.sport.game.Game;
import com.backyard.playground.tools.FanOut;

@Service
public class SportService {
    private final YahooSportsClient yahooSportsClient;
    private final GameService gameService;
    private final FanOut fanOut;

    public SportService(YahooSportsClient yahooSportsClient, GameService gameService,
            FanOut fanOut) {
        this.yahooSportsClient = yahooSportsClient;
        this.gameService = gameService;
        this.fanOut = fanOut;
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
        return fanOut.map(
                games,
                g -> cached ? gameService.getGameDetails(g.gameId()) : gameService.fetchGameDetails(g.gameId()),
                "get-game-details",
                g -> g.gameId());
    }
}
