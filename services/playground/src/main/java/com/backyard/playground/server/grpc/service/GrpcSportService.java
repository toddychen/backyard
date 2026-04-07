package com.backyard.playground.server.grpc.service;

import com.backyard.playground.data.model.sport.GameDTO;
import com.backyard.playground.service.GameService;
import com.backyard.playground.service.SportService;
import com.backyard.playground.grpc.sport.GameResponse;
import com.backyard.playground.grpc.sport.GetGameDetailsRequest;
import com.backyard.playground.grpc.sport.GetTeamGamesResponse;
import com.backyard.playground.grpc.sport.GetTeamGamesRequest;
import com.backyard.playground.grpc.sport.SportGrpc;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class GrpcSportService extends SportGrpc.SportImplBase {

    private static final Logger log = LoggerFactory.getLogger(GrpcSportService.class);

    private final SportService sportService;
    private final GameService gameService;

    public GrpcSportService(SportService sportService, GameService gameService) {
        this.sportService = sportService;
        this.gameService = gameService;
    }

    @Override
    public void getTeamGames(
            GetTeamGamesRequest request,
            StreamObserver<GetTeamGamesResponse> responseObserver) {
        log.info("getTeamGames: teamId={} next={} last={}",
                request.getTeamId(), request.getNextX(), request.getLastX());
        List<GameDTO> games = sportService.getTeamGamesParallel(request.getTeamId(), request.getNextX(),
                request.getLastX(), true);
        GetTeamGamesResponse resp = GetTeamGamesResponse.newBuilder()
                .addAllGames(games.stream().map(GrpcSportService::toReply).toList())
                .build();
        responseObserver.onNext(resp);
        responseObserver.onCompleted();
    }

    @Override
    public void getGameDetails(
            GetGameDetailsRequest request,
            StreamObserver<GameResponse> responseObserver) {
        log.info("getGameDetails: gameId={}", request.getGameId());
        GameDTO game = gameService.getGameDetails(request.getGameId());
        if (game == null) {
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription("game not found: " + request.getGameId())
                    .asRuntimeException());
            return;
        }
        responseObserver.onNext(toReply(game));
        responseObserver.onCompleted();
    }

    private static GameResponse toReply(GameDTO g) {
        return GameResponse.newBuilder()
                .setGameId(g.gameId())
                .setHomeTeamFullName(g.homeTeamFullName())
                .setAwayTeamFullName(g.awayTeamFullName())
                .setHomeTeamAbbrev(g.homeTeamAbbrev())
                .setAwayTeamAbbrev(g.awayTeamAbbrev())
                .setStartTime(g.startTime())
                .setGameStatus(g.gameStatus())
                .setHomeScore(g.homeScore())
                .setAwayScore(g.awayScore())
                .build();
    }
}
