package com.backyard.playground.client.grpc.interceptor;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.MethodDescriptor;

import java.util.concurrent.TimeUnit;

/**
 * Applies a default deadline to every outbound gRPC call on the channel. Attach
 * once per channel in {@code GrpcClientConfig}:
 * {@code .intercept(new GrpcClientDeadlineInterceptor(30))}.
 */
public class GrpcClientDeadlineInterceptor implements ClientInterceptor {

    private final long seconds;

    public GrpcClientDeadlineInterceptor(long seconds) {
        this.seconds = seconds;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        if (callOptions.getDeadline() == null) {
            callOptions = callOptions.withDeadlineAfter(seconds, TimeUnit.SECONDS);
        }
        return next.newCall(method, callOptions);
    }
}
