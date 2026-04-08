package com.backyard.playground.client.grpc;

import com.google.cloud.language.v2.AnalyzeEntitiesRequest;
import com.google.cloud.language.v2.AnalyzeEntitiesResponse;
import com.google.cloud.language.v2.AnalyzeSentimentRequest;
import com.google.cloud.language.v2.AnalyzeSentimentResponse;
import com.google.cloud.language.v2.Document;
import com.google.cloud.language.v2.Document.Type;
import com.google.cloud.language.v2.LanguageServiceGrpc.LanguageServiceBlockingStub;

public class GoogleLanguageClientImpl extends BaseClient implements GoogleLanguageClient {

    private final LanguageServiceBlockingStub stub;

    public GoogleLanguageClientImpl(LanguageServiceBlockingStub stub) {
        this.stub = stub;
    }

    @Override
    public AnalyzeSentimentResponse analyzeSentiment(String text) {
        return rpc(() -> stub.analyzeSentiment(
                AnalyzeSentimentRequest.newBuilder()
                        .setDocument(Document.newBuilder().setContent(text).setType(Type.PLAIN_TEXT))
                        .build()));
    }

    @Override
    public AnalyzeEntitiesResponse analyzeEntities(String text) {
        return rpc(() -> stub.analyzeEntities(
                AnalyzeEntitiesRequest.newBuilder()
                        .setDocument(Document.newBuilder().setContent(text).setType(Type.PLAIN_TEXT))
                        .build()));
    }
}
