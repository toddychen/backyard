package com.backyard.playground.data.persist.cassandra.chat.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.stereotype.Repository;

import com.backyard.playground.data.persist.cassandra.chat.entity.MessageByChannel;

/**
 * Read and insert access for messages_by_channel.
 *
 * The cursor is a UUID v7 message_id. Because UUID v7 encodes the
 * creation timestamp in its high 48 bits, comparing message_id values
 * is equivalent to comparing creation times — no separate created_at
 * column or bucket parameter needed.
 *
 * Partial column updates (edit body, set deleted, set has_thread) are
 * handled by {@link MessagesByChannelRepositoryCustom} so ChatService
 * never touches CassandraOperations directly.
 */
@Repository
public interface MessagesByChannelRepository
        extends CassandraRepository<MessageByChannel, UUID>, MessagesByChannelRepositoryCustom {

    /**
     * Latest page for a channel feed (newest first).
     * Used for initial channel open when no cursor is held.
     */
    @Query("SELECT * FROM chat.messages_by_channel "
            + "WHERE channel_id = ?0 "
            + "LIMIT ?1")
    List<MessageByChannel> findPage(UUID channelId, int limit);

    /**
     * Paginate backward (older messages) from a cursor.
     * beforeId is the message_id of the oldest message on the current page.
     */
    @Query("SELECT * FROM chat.messages_by_channel "
            + "WHERE channel_id = ?0 "
            + "AND message_id < ?1 "
            + "LIMIT ?2")
    List<MessageByChannel> findPageBefore(UUID channelId, UUID beforeId, int limit);

    /**
     * Fetch a single message by channel and message ID.
     * ALLOW FILTERING is bounded to the channel's partitions.
     */
    @Query("SELECT * FROM chat.messages_by_channel "
            + "WHERE channel_id = ?0 AND message_id = ?1 ALLOW FILTERING")
    java.util.Optional<MessageByChannel> findByChannelIdAndMessageId(UUID channelId, UUID messageId);

    /**
     * Check that a message belongs to the given channel.
     * Scans only that channel's buckets (channel_id is the partition key)
     * so ALLOW FILTERING is bounded by the channel's data, not the full table.
     */
    @Query("SELECT count(*) FROM chat.messages_by_channel "
            + "WHERE channel_id = ?0 AND message_id = ?1 ALLOW FILTERING")
    long countByChannelIdAndMessageId(UUID channelId, UUID messageId);

}
