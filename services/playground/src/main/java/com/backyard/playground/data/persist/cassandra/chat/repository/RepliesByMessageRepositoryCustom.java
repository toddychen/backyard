package com.backyard.playground.data.persist.cassandra.chat.repository;

import java.util.UUID;

public interface RepliesByMessageRepositoryCustom {

    void updateBody(UUID parentId, UUID messageId, String newBody);

    void markDeleted(UUID parentId, UUID messageId);
}
