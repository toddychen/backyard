package com.backyard.playground.client.grpc;

import com.google.cloud.language.v2.AnalyzeEntitiesResponse;
import com.google.cloud.language.v2.AnalyzeSentimentResponse;

/**
 * Outbound gRPC client for the Google Cloud Natural Language API.
 *
 * <p>
 * Wraps the generated {@code LanguageServiceBlockingStub} behind an interface
 * so services remain unaware of the gRPC transport and the implementation can
 * be swapped or mocked in tests.
 */
public interface GoogleLanguageClient {

    /** Analyzes the sentiment of the given text. */
    AnalyzeSentimentResponse analyzeSentiment(String text);

    /** Identifies entities (people, places, organizations, etc.) in the text. */
    AnalyzeEntitiesResponse analyzeEntities(String text);
}
