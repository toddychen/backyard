package com.backyard.playground.data.persist.mysql.chat;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.backyard.playground.data.persist.mysql.chat.Channel.ChannelType;

@Repository
public interface ChannelRepository extends JpaRepository<Channel, UUID> {

    List<Channel> findByType(ChannelType type);

    /** Update the last message ID on a channel after every message send. */
    @Modifying
    @Transactional
    @Query("UPDATE Channel c SET c.lastMessageId = :messageId WHERE c.id = :channelId")
    int updateLastMessage(UUID channelId, UUID messageId);
}
