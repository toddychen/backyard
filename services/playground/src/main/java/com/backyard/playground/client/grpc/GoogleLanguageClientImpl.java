package com.backyard.playground.client.grpc;

import com.google.cloud.language.v2.AnalyzeEntitiesRequest;
import com.google.cloud.language.v2.AnalyzeEntitiesResponse;
import com.google.cloud.language.v2.AnalyzeSentimentRequest;
import com.google.cloud.language.v2.AnalyzeSentimentResponse;
import com.google.cloud.language.v2.Document;
import com.google.cloud.language.v2.Document.Type;
import com.google.cloud.language.v2.LanguageServiceGrpc.LanguageServiceBlockingStub;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;

public class GoogleLanguageClientImpl extends BaseClient implements GoogleLanguageClient {

    private static final String CLIENT_NAME = "google_language";

    private final LanguageServiceBlockingStub stub;

    public GoogleLanguageClientImpl(
            LanguageServiceBlockingStub stub,
            CircuitBreakerRegistry cbRegistry,
            RetryRegistry retryRegistry) {
        super(cbRegistry, retryRegistry);
        this.stub = stub;
    }

    @Override
    public AnalyzeSentimentResponse analyzeSentiment(String text) {
        return rpc(CLIENT_NAME, "analyzeSentiment",
                () -> stub.analyzeSentiment(AnalyzeSentimentRequest.newBuilder()
                        .setDocument(Document.newBuilder().setContent(text).setType(Type.PLAIN_TEXT))
                        .build()));
    }

    @Override
    public AnalyzeEntitiesResponse analyzeEntities(String text) {
        return rpc(CLIENT_NAME, "analyzeEntities",
                () -> stub.analyzeEntities(AnalyzeEntitiesRequest.newBuilder()
                        .setDocument(Document.newBuilder().setContent(text).setType(Type.PLAIN_TEXT))
                        .build()));
    }
}
