package com.backyard.playground.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backyard.playground.service.SportService;

@RestController
@RequestMapping("/{version}/sport")
public class SportController extends BaseController {

    private final SportService sportService;

    public SportController(SportService sportService) {
        this.sportService = sportService;
    }

    @GetMapping(value = "/team/{teamId}/games", version = "1+")
    public ResponseEntity<Object> getTeamGames(
            @PathVariable("teamId") String teamId,
            @RequestParam(value = "next_x", defaultValue = "2") int nextX,
            @RequestParam(value = "last_x", defaultValue = "2") int lastX,
            @RequestParam(value = "parallel", defaultValue = "false") boolean parallel) {
        return ok(parallel
                ? sportService.getTeamGamesParallel(teamId, nextX, lastX)
                : sportService.getTeamGames(teamId, nextX, lastX));
    }

    @GetMapping(value = "/game/{gameId}/details", version = "1+")
    public ResponseEntity<Object> getGameDetails(@PathVariable("gameId") String gameId) {
        return ok(sportService.getGameDetails(gameId));
    }
}
