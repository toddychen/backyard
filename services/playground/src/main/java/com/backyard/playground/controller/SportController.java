package com.backyard.playground.controller;

import com.backyard.playground.data.sport.game.Game;
import com.backyard.playground.service.GameService;
import com.backyard.playground.service.SportService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Sport", description = "Sports games and scores")
@RestController
@RequestMapping("/{version}/sport")
public class SportController extends BaseController {

    private final SportService sportService;
    private final GameService gameService;

    public SportController(SportService sportService, GameService gameService) {
        this.sportService = sportService;
        this.gameService = gameService;
    }

    @Operation(summary = "Get upcoming and past games for a team")
    @ApiResponses({
            @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = Game.class)))),
            @ApiResponse(responseCode = "503", content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class)))
    })
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

    @Operation(summary = "Get details for a single game")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Game details, or null if the game ID is unknown", content = @Content(schema = @Schema(implementation = Game.class))),
            @ApiResponse(responseCode = "503", content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class)))
    })
    @GetMapping(value = "/game/{gameId}/details", version = "1+")
    public ResponseEntity<Object> getGameDetails(@PathVariable("gameId") String gameId) {
        return ok(gameService.getGameDetails(gameId));
    }
}
