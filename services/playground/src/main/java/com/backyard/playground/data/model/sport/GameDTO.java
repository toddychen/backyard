package com.backyard.playground.data.model.sport;

import com.backyard.playground.cache.Expirable;
import com.backyard.playground.data.api.yahoo.YahooGame;

import java.time.Duration;

public record GameDTO(
        String gameId,
        String homeTeamFullName,
        String awayTeamFullName,
        String homeTeamAbbrev,
        String awayTeamAbbrev,
        String startTime,
        String gameStatus,
        int homeScore,
        int awayScore)
        implements Expirable {

    @Override
    public Duration cacheDuration() {
        return "STARTED".equals(gameStatus) ? Duration.ofSeconds(10) : Duration.ofSeconds(60);
    }

    public static GameDTO from(YahooGame g) {
        return new GameDTO(
                g.gameId(),
                g.homeTeamFullName(),
                g.awayTeamFullName(),
                g.homeTeamAbbrev(),
                g.awayTeamAbbrev(),
                g.startTime(),
                g.gameStatus(),
                g.homeScore(),
                g.awayScore());
    }
}
