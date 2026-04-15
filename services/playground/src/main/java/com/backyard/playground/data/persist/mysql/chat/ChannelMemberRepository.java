package com.backyard.playground.data.persist.mysql.chat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface ChannelMemberRepository
        extends JpaRepository<ChannelMember, ChannelMember.ChannelMemberId> {

    /** All members of a channel — used for member list panel. */
    List<ChannelMember> findByIdChannelId(UUID channelId);

    /** All channels a user has joined — used for sidebar. */
    List<ChannelMember> findByIdUserId(UUID userId);

    /** Check membership before allowing message send or history read. */
    boolean existsByIdChannelIdAndIdUserId(UUID channelId, UUID userId);

    /** Update the user's last-read position in a channel. */
    @Modifying
    @Transactional
    @Query("UPDATE ChannelMember cm SET cm.lastReadMessageId = :messageId "
            + "WHERE cm.id.channelId = :channelId AND cm.id.userId = :userId")
    int updateLastRead(UUID channelId, UUID userId, UUID messageId);

    /** Fetch the last-read message ID for the unread check. */
    @Query("SELECT cm.lastReadMessageId FROM ChannelMember cm "
            + "WHERE cm.id.channelId = :channelId AND cm.id.userId = :userId")
    Optional<UUID> findLastReadMessageId(UUID channelId, UUID userId);

}
