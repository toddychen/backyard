package com.backyard.playground.client.grpc;

import com.grpcbin.AddGrpc.AddBlockingStub;
import com.grpcbin.ConcatRequest;
import com.grpcbin.SumRequest;

public class GrpcbinClientImpl extends BaseClient implements GrpcbinClient {

    private final AddBlockingStub stub;

    public GrpcbinClientImpl(AddBlockingStub stub) {
        this.stub = stub;
    }

    @Override
    public long sum(long a, long b) {
        return rpc(() -> stub.sum(SumRequest.newBuilder().setA(a).setB(b).build())).getV();
    }

    @Override
    public String concat(String a, String b) {
        return rpc(() -> stub.concat(ConcatRequest.newBuilder().setA(a).setB(b).build())).getV();
    }
}
