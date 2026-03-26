package com.backyard.playground.client;

import java.util.List;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

import com.backyard.playground.data.api.yahoo.YahooGame;

@HttpExchange("/api/v8")
public interface YahooSportsClient {

    @GetExchange("/team/{teamId}/games")
    List<YahooGame> getTeamGames(
            @PathVariable("teamId") String teamId,
            @RequestParam("next_x") int nextX,
            @RequestParam("last_x") int lastX);

    @GetExchange("/game/{gameId}/details")
    YahooGame getGameDetails(@PathVariable("gameId") String gameId);
}
