package com.backyard.playground.data.persist.cassandra.chat.repository;

import java.util.UUID;

import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.data.cassandra.core.query.Criteria;
import org.springframework.data.cassandra.core.query.Query;
import org.springframework.data.cassandra.core.query.Update;

import com.backyard.playground.data.persist.cassandra.chat.entity.ReplyByMessage;

public class RepliesByMessageRepositoryImpl implements RepliesByMessageRepositoryCustom {

    private final CassandraOperations cassandraOps;

    public RepliesByMessageRepositoryImpl(CassandraOperations cassandraOps) {
        this.cassandraOps = cassandraOps;
    }

    @Override
    public void updateBody(UUID parentId, UUID messageId, String newBody) {
        cassandraOps.update(
                Query.query(
                        Criteria.where("parent_id").is(parentId),
                        Criteria.where("message_id").is(messageId)),
                Update.empty().set("body", newBody).set("edited", true),
                ReplyByMessage.class);
    }

    @Override
    public void markDeleted(UUID parentId, UUID messageId) {
        cassandraOps.update(
                Query.query(
                        Criteria.where("parent_id").is(parentId),
                        Criteria.where("message_id").is(messageId)),
                Update.empty().set("deleted", true),
                ReplyByMessage.class);
    }
}
