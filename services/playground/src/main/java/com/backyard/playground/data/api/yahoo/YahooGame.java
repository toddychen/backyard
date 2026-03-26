package com.backyard.playground.data.api.yahoo;

import com.fasterxml.jackson.annotation.JsonProperty;

// Yahoo Sports API uses PascalCase keys — @JsonProperty maps each field
public record YahooGame(
        @JsonProperty("GameId") String gameId,
        @JsonProperty("HomeTeamFullName") String homeTeamFullName,
        @JsonProperty("AwayTeamFullName") String awayTeamFullName,
        @JsonProperty("HomeTeamAbbrev") String homeTeamAbbrev,
        @JsonProperty("AwayTeamAbbrev") String awayTeamAbbrev,
        @JsonProperty("StartTime") String startTime,
        @JsonProperty("GameStatus") String gameStatus,
        @JsonProperty("HomeScore") int homeScore,
        @JsonProperty("AwayScore") int awayScore) {
}
