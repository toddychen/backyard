package com.backyard.playground.server.grpc.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.backyard.playground.grpc.echo.EchoGrpc;
import com.backyard.playground.grpc.echo.EchoResponse;
import com.backyard.playground.grpc.echo.EchoRequest;

import io.grpc.stub.StreamObserver;

@Service
public class GrpcEchoService extends EchoGrpc.EchoImplBase {

    private static final Logger log = LoggerFactory.getLogger(GrpcEchoService.class);

    @Override
    public void echoUnary(
            EchoRequest request,
            StreamObserver<EchoResponse> responseObserver) {
        log.info("echoUnary: {}", request.getMessage());
        EchoResponse reply = EchoResponse.newBuilder()
                .setMessage("echo: " + request.getMessage())
                .setIndex(0)
                .build();
        responseObserver.onNext(reply);
        responseObserver.onCompleted();
    }

    @Override
    public void echoServerStream(
            EchoRequest request,
            StreamObserver<EchoResponse> responseObserver) {
        log.info("echoServerStream: {} x{}", request.getMessage(), request.getCount());
        int count = request.getCount() > 0 ? request.getCount() : 3;
        for (int i = 0; i < count; i++) {
            responseObserver.onNext(EchoResponse.newBuilder()
                    .setMessage("echo: " + request.getMessage())
                    .setIndex(i)
                    .build());
        }
        responseObserver.onCompleted();
    }
}
