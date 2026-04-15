package com.backyard.playground.data.persist.mysql.chat;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Maps a sorted user pair to its PRIVATE channel.
 *
 * user_id_1 is always min(userA, userB) and user_id_2 is always max(userA,
 * userB). The unique constraint on the pair guarantees exactly one channel
 * exists between any two users, enforced at the DB level regardless of who
 * initiates.
 *
 * The unique constraint also serves as the composite index for the only query
 * this table runs: WHERE user_id_1 = ? AND user_id_2 = ? No additional index on
 * user_id_2 is needed — "all DMs for a user" is answered by channel_members,
 * not this table.
 */
@Entity
@Table(name = "dm_channels", //
        uniqueConstraints = @UniqueConstraint(name = "uq_dm_pair", columnNames = { "user_id_1", "user_id_2" }))
public class DmChannel {

    /** Same ID as the corresponding row in channels. */
    @Id
    @Column(name = "channel_id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID channelId;

    /** The smaller of the two user UUIDs (canonical ordering). */
    @Column(name = "user_id_1", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID userId1;

    /** The larger of the two user UUIDs (canonical ordering). */
    @Column(name = "user_id_2", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID userId2;

    protected DmChannel() {
    }

    public DmChannel(UUID channelId, UUID userA, UUID userB) {
        this.channelId = channelId;
        // Store in canonical order so the unique constraint covers both directions
        if (userA.compareTo(userB) <= 0) {
            this.userId1 = userA;
            this.userId2 = userB;
        } else {
            this.userId1 = userB;
            this.userId2 = userA;
        }
    }

    public UUID getChannelId() {
        return channelId;
    }

    public UUID getUserId1() {
        return userId1;
    }

    public UUID getUserId2() {
        return userId2;
    }
}
