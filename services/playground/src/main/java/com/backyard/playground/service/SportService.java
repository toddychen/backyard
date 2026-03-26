package com.backyard.playground.service;

import java.util.List;

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

    public SportService(YahooSportsClient yahooSportsClient) {
        this.yahooSportsClient = yahooSportsClient;
    }

    public List<Game> getTeamGames(String teamId, int nextX, int lastX) {
        log.info("fetching games for teamId='{}' next={} last={}", teamId, nextX, lastX);
        List<YahooGame> games = yahooSportsClient.getTeamGames(teamId, nextX, lastX);
        if (games == null)
            return List.of();
        return games.stream().map(Game::from).toList();
    }

    // unless: skip caching null results (e.g. game not found) — avoids
    // storing a transient absence that could mask a later valid response
    @Cacheable(value = CacheConfig.CACHE_GAME_DETAILS, key = "#gameId", unless = "#result == null")
    public Game getGameDetails(String gameId) {
        log.info("fetching game details for gameId='{}'", gameId);
        YahooGame game = yahooSportsClient.getGameDetails(gameId);
        return game == null ? null : Game.from(game);
    }
}
