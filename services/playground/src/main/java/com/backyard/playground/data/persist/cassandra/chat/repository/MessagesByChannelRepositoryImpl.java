package com.backyard.playground.data.persist.cassandra.chat.repository;

import java.util.UUID;

import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.data.cassandra.core.query.Criteria;
import org.springframework.data.cassandra.core.query.Query;
import org.springframework.data.cassandra.core.query.Update;

import com.backyard.playground.data.persist.cassandra.chat.entity.MessageByChannel;

public class MessagesByChannelRepositoryImpl implements MessagesByChannelRepositoryCustom {

    private final CassandraOperations cassandraOps;

    public MessagesByChannelRepositoryImpl(CassandraOperations cassandraOps) {
        this.cassandraOps = cassandraOps;
    }

    @Override
    public void updateBody(UUID channelId, UUID messageId, String newBody) {
        cassandraOps.update(
                Query.query(
                        Criteria.where("channel_id").is(channelId),
                        Criteria.where("message_id").is(messageId)),
                Update.empty().set("body", newBody).set("edited", true),
                MessageByChannel.class);
    }

    @Override
    public void markDeleted(UUID channelId, UUID messageId) {
        cassandraOps.update(
                Query.query(
                        Criteria.where("channel_id").is(channelId),
                        Criteria.where("message_id").is(messageId)),
                Update.empty().set("deleted", true),
                MessageByChannel.class);
    }

    @Override
    public void markHasThread(UUID channelId, UUID messageId) {
        cassandraOps.update(
                Query.query(
                        Criteria.where("channel_id").is(channelId),
                        Criteria.where("message_id").is(messageId)),
                Update.empty().set("has_thread", true),
                MessageByChannel.class);
    }
}
