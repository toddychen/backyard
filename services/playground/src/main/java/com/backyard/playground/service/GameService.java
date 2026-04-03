package com.backyard.playground.service;

import com.backyard.playground.cache.CacheConfig;
import com.backyard.playground.client.YahooSportsClient;
import com.backyard.playground.data.api.yahoo.YahooGame;
import com.backyard.playground.data.sport.game.Game;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class GameService {

    private final YahooSportsClient yahooSportsClient;

    public GameService(YahooSportsClient yahooSportsClient) {
        this.yahooSportsClient = yahooSportsClient;
    }

    // Always fetches from the network — bypasses cache
    public Game fetchGameDetails(String gameId) {
        YahooGame game = yahooSportsClient.getGameDetails(gameId);
        return game == null ? null : Game.from(game);
    }

    // Cache-first — key includes the full BCP 47 locale tag so zh-TW and
    // zh-CN (different scripts) get separate cache entries. unless: skip
    // caching null results to avoid masking a later valid response.
    @Cacheable(value = CacheConfig.CACHE_GAME_DETAILS, key = "#gameId + ':' + T(org.springframework.context.i18n.LocaleContextHolder).locale.toLanguageTag()", unless = "#result == null")
    public Game getGameDetails(String gameId) {
        return fetchGameDetails(gameId);
    }
}
