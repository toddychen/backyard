package com.backyard.playground.client.grpc.config;

import com.backyard.playground.client.grpc.interceptor.GrpcClientDeadlineInterceptor;
import com.backyard.playground.client.grpc.interceptor.GrpcClientLoggingInterceptor;
import com.backyard.playground.client.grpc.GoogleLanguageClient;
import com.backyard.playground.client.grpc.GoogleLanguageClientImpl;
import com.backyard.playground.client.grpc.GrpcbinClient;
import com.backyard.playground.client.grpc.GrpcbinClientImpl;
import com.google.cloud.language.v2.LanguageServiceGrpc;
import com.grpcbin.AddGrpc;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;

/**
 * Wires all outbound gRPC client beans.
 *
 * <p>
 * Each client is built in two layers:
 * <ol>
 * <li>A {@link ManagedChannel} to the remote endpoint with TLS + auth metadata
 * attached via a client interceptor.
 * <li>A {@link GoogleLanguageClient} interface wrapping the generated
 * {@link LanguageServiceGrpc.LanguageServiceBlockingStub}.
 * </ol>
 *
 * <p>
 * The channel is registered as a destroyable bean — Spring calls
 * {@code ManagedChannel.shutdown()} on context close.
 */
@Configuration
public class GrpcClientConfig {

    private static final Metadata.Key<String> API_KEY_HEADER = Metadata.Key.of("x-goog-api-key",
            Metadata.ASCII_STRING_MARSHALLER);

    @Bean(name = "googleLanguageChannel", destroyMethod = "shutdown")
    public ManagedChannel googleLanguageChannel(
            @Value("${google.language.api-key}") String apiKey)
            throws Exception {
        Metadata metadata = new Metadata();
        metadata.put(API_KEY_HEADER, apiKey);

        return NettyChannelBuilder
                .forAddress("language.googleapis.com", 443)
                .sslContext(GrpcSslContexts.forClient().build())
                .intercept(MetadataUtils.newAttachHeadersInterceptor(metadata))
                .intercept(new GrpcClientDeadlineInterceptor(30))
                .intercept(new GrpcClientLoggingInterceptor("google_language"))
                .build();
    }

    @Bean
    public GoogleLanguageClient languageClient(
            @Qualifier("googleLanguageChannel") ManagedChannel googleLanguageChannel,
            CircuitBreakerRegistry cbRegistry,
            RetryRegistry retryRegistry) {
        LanguageServiceGrpc.LanguageServiceBlockingStub stub = LanguageServiceGrpc
                .newBlockingStub(googleLanguageChannel);
        return new GoogleLanguageClientImpl(stub, cbRegistry, retryRegistry);
    }

    @Bean(name = "grpcbinChannel", destroyMethod = "shutdown")
    public ManagedChannel grpcbinChannel() throws Exception {
        return NettyChannelBuilder
                .forAddress("grpcb.in", 9001)
                .sslContext(GrpcSslContexts.forClient().build())
                .intercept(new GrpcClientDeadlineInterceptor(10))
                .intercept(new GrpcClientLoggingInterceptor("grpcbin"))
                .build();
    }

    @Bean
    public GrpcbinClient grpcbinClient(
            @Qualifier("grpcbinChannel") ManagedChannel grpcbinChannel,
            CircuitBreakerRegistry cbRegistry,
            RetryRegistry retryRegistry) {
        return new GrpcbinClientImpl(AddGrpc.newBlockingStub(grpcbinChannel), cbRegistry, retryRegistry);
    }
}
