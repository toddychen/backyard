package com.backyard.playground.data.persist.cassandra.chat.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.stereotype.Repository;

import com.backyard.playground.data.persist.cassandra.chat.entity.ReplyByMessage;

/**
 * Read and insert access for replies_by_message.
 *
 * Cursor is a UUID v7 message_id (oldest-first thread order).
 * Updates (set deleted) are performed via CassandraOperations in ChatService.
 */
@Repository
public interface RepliesByMessageRepository
        extends CassandraRepository<ReplyByMessage, UUID>, RepliesByMessageRepositoryCustom {

    /** All replies for a thread, oldest first. Used on initial thread open. */
    @Query("SELECT * FROM chat.replies_by_message "
            + "WHERE parent_id = ?0 "
            + "LIMIT ?1")
    List<ReplyByMessage> findReplies(UUID parentId, int limit);

    /**
     * Fetch a single reply by parent and message ID.
     * ALLOW FILTERING is bounded to that parent's partition.
     */
    @Query("SELECT * FROM chat.replies_by_message "
            + "WHERE parent_id = ?0 AND message_id = ?1 ALLOW FILTERING")
    java.util.Optional<ReplyByMessage> findByParentIdAndMessageId(UUID parentId, UUID messageId);

    /** Paginate forward (newer replies) from a cursor. */
    @Query("SELECT * FROM chat.replies_by_message "
            + "WHERE parent_id = ?0 "
            + "AND message_id > ?1 "
            + "LIMIT ?2")
    List<ReplyByMessage> findRepliesAfter(UUID parentId, UUID afterId, int limit);
}
