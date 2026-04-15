package com.backyard.playground.data.persist.mysql.chat;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DmChannelRepository extends JpaRepository<DmChannel, UUID> {

    /**
     * Find the PRIVATE channel for a user pair.
     * Callers must pass IDs in canonical order: userId1 = min, userId2 = max.
     */
    Optional<DmChannel> findByUserId1AndUserId2(UUID userId1, UUID userId2);

    /** Look up a DM channel by its channel ID (for membership checks). */
    Optional<DmChannel> findByChannelId(UUID channelId);

    /** Bulk PK lookup — used when building the channel list sidebar. */
    List<DmChannel> findByChannelIdIn(Collection<UUID> channelIds);

    /**
     * All DM channels a user is a participant in — used for sidebar.
     * Pass the same userId for both parameters (OR across both columns).
     */
    List<DmChannel> findByUserId1OrUserId2(UUID userId1, UUID userId2);
}
