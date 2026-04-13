package com.backyard.playground.service;

import com.backyard.playground.client.reactive.WikimediaStreamClient;

import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;

/** Exposes the Wikimedia EventStreams recent-change feed. */
@Service
public class WikimediaService {

    private final WikimediaStreamClient wikimediaStreamClient;

    public WikimediaService(WikimediaStreamClient wikimediaStreamClient) {
        this.wikimediaStreamClient = wikimediaStreamClient;
    }

    public Flux<ServerSentEvent<String>> streamRecentChanges() {
        return wikimediaStreamClient.streamRecentChanges();
    }
}
