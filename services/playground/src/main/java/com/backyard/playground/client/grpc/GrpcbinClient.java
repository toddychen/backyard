package com.backyard.playground.client.grpc;

/**
 * Outbound gRPC client for the grpcb.in Add service.
 */
public interface GrpcbinClient {

    /** Returns the sum of a and b. */
    long sum(long a, long b);

    /** Returns the concatenation of a and b. */
    String concat(String a, String b);
}
