package com.backyard.playground.data.persist.cassandra.chat.repository;

import java.util.UUID;

/** Partial-update operations on messages_by_channel. */
public interface MessagesByChannelRepositoryCustom {

    void updateBody(UUID channelId, UUID messageId, String newBody);

    void markDeleted(UUID channelId, UUID messageId);

    void markHasThread(UUID channelId, UUID messageId);
}
