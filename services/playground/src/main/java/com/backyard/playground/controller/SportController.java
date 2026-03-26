package com.backyard.playground.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backyard.playground.service.GameService;
import com.backyard.playground.service.SportService;

@RestController
@RequestMapping("/{version}/sport")
public class SportController extends BaseController {

    private final SportService sportService;
    private final GameService gameService;

    public SportController(SportService sportService, GameService gameService) {
        this.sportService = sportService;
        this.gameService = gameService;
    }

    @GetMapping(value = "/team/{teamId}/games", version = "1+")
    public ResponseEntity<Object> getTeamGames(
            @PathVariable("teamId") String teamId,
            @RequestParam(value = "next_x", defaultValue = "2") int nextX,
            @RequestParam(value = "last_x", defaultValue = "2") int lastX,
            @RequestParam(value = "parallel", defaultValue = "true") boolean parallel,
            @RequestParam(value = "cached", defaultValue = "true") boolean cached) {
        if (parallel)
            return ok(sportService.getTeamGamesParallel(teamId, nextX, lastX, cached));
        return ok(sportService.getTeamGames(teamId, nextX, lastX, cached));
    }

    @GetMapping(value = "/game/{gameId}/details", version = "1+")
    public ResponseEntity<Object> getGameDetails(@PathVariable("gameId") String gameId) {
        return ok(gameService.getGameDetails(gameId));
    }
}
