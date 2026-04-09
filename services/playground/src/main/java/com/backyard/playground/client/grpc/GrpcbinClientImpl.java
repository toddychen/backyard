package com.backyard.playground.client.grpc;

import com.grpcbin.AddGrpc.AddBlockingStub;
import com.grpcbin.ConcatRequest;
import com.grpcbin.SumRequest;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;

public class GrpcbinClientImpl extends BaseClient implements GrpcbinClient {

    private static final String CLIENT_NAME = "grpcbin";

    private final AddBlockingStub stub;

    public GrpcbinClientImpl(
            AddBlockingStub stub,
            CircuitBreakerRegistry cbRegistry,
            RetryRegistry retryRegistry) {
        super(cbRegistry, retryRegistry);
        this.stub = stub;
    }

    @Override
    public long sum(long a, long b) {
        return rpc(CLIENT_NAME, "sum", () -> stub.sum(SumRequest.newBuilder().setA(a).setB(b).build())).getV();
    }

    @Override
    public String concat(String a, String b) {
        return rpc(CLIENT_NAME, "concat", () -> stub.concat(ConcatRequest.newBuilder().setA(a).setB(b).build())).getV();
    }
}
