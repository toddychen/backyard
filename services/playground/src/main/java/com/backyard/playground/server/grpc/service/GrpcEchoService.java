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
            try {
                // Intentional delay to demonstrate server-streaming behavior:
                // each EchoResponse is sent and received by the client independently
                // before all messages are ready. With grpcurl, responses print one
                // by one 500ms apart. In Jaeger, each "sent" event is spaced 500ms
                // apart on the span timeline. A blocking stub client processes each
                // message via Iterator.next() as it arrives without waiting for all.
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        responseObserver.onCompleted();
    }
}
