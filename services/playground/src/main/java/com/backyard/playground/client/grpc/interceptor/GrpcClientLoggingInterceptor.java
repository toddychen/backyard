package com.backyard.playground.client.grpc.interceptor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;

/**
 * Logs every outbound gRPC call: method name on request, status + duration on
 * response. Construct with a client name for log context:
 * {@code new GrpcClientLoggingInterceptor("google_language")}.
 */
public class GrpcClientLoggingInterceptor implements ClientInterceptor {

    private static final Logger log = LoggerFactory.getLogger(GrpcClientLoggingInterceptor.class);

    private final String clientName;

    public GrpcClientLoggingInterceptor(String clientName) {
        this.clientName = clientName;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
            CallOptions callOptions, Channel next) {
        long start = System.currentTimeMillis();
        String methodName = method.getFullMethodName();
        return new SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                super.start(
                        new SimpleForwardingClientCallListener<>(responseListener) {
                            @Override
                            public void onClose(Status status, Metadata trailers) {
                                long duration = System.currentTimeMillis() - start;
                                log.info("[{}] {} {} {}ms", clientName, methodName, status.getCode(), duration);
                                super.onClose(status, trailers);
                            }
                        },
                        headers);
            }
        };
    }
}
