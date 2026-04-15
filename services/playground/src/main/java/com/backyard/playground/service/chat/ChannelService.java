package com.backyard.playground.service.chat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backyard.playground.data.model.UserDTO;
import com.backyard.playground.data.model.chat.ChannelDTO;
import com.backyard.playground.data.model.chat.ChannelListDTO;
import com.backyard.playground.data.model.chat.CreateChannelInputDTO;
import com.backyard.playground.data.persist.mysql.auth.UserRepository;
import com.backyard.playground.data.persist.mysql.chat.Channel;
import com.backyard.playground.data.persist.mysql.chat.Channel.ChannelType;
import com.backyard.playground.data.persist.mysql.chat.ChannelMember;
import com.backyard.playground.data.persist.mysql.chat.ChannelMemberRepository;
import com.backyard.playground.data.persist.mysql.chat.ChannelRepository;
import com.backyard.playground.data.persist.mysql.chat.DmChannel;
import com.backyard.playground.data.persist.mysql.chat.DmChannelRepository;
import com.backyard.playground.exception.BadRequestException;
import com.backyard.playground.exception.NotFoundException;

@Service
public class ChannelService {

    private final ChannelRepository channelRepository;
    private final ChannelMemberRepository memberRepository;
    private final DmChannelRepository dmChannelRepository;
    private final UserRepository userRepository;

    public ChannelService(
            ChannelRepository channelRepository,
            ChannelMemberRepository memberRepository,
            DmChannelRepository dmChannelRepository,
            UserRepository userRepository) {
        this.channelRepository = channelRepository;
        this.memberRepository = memberRepository;
        this.dmChannelRepository = dmChannelRepository;
        this.userRepository = userRepository;
    }

    /** Create a public channel and auto-join the creator. */
    @Transactional("mysqlTransactionManager")
    public ChannelDTO createChannel(UUID creatorId, CreateChannelInputDTO input) {
        ChannelType type = ChannelType.valueOf(input.getType() != null ? input.getType() : "PUBLIC");
        Channel channel = new Channel(input.getName(), type);
        channelRepository.save(channel);
        memberRepository.save(new ChannelMember(channel.getId(), creatorId));
        return ChannelDTO.from(channel);
    }

    /** Join an existing channel. No-op if already a member. Returns the channel DTO. */
    @Transactional("mysqlTransactionManager")
    public ChannelDTO joinChannel(UUID channelId, UUID userId) {
        Channel channel = channelRepository.findById(channelId)
                .orElseThrow(() -> new NotFoundException("Channel not found: " + channelId));
        if (!memberRepository.existsByIdChannelIdAndIdUserId(channelId, userId)) {
            memberRepository.save(new ChannelMember(channelId, userId));
        }
        return ChannelDTO.from(channel);
    }

    /** Leave a channel. */
    @Transactional("mysqlTransactionManager")
    public void leaveChannel(UUID channelId, UUID userId) {
        memberRepository.deleteById(new ChannelMember.ChannelMemberId(channelId, userId));
    }

    /**
     * Channel IDs for PUBLIC/PRIVATE channels the user has joined. Excludes DM
     * channels — DM messages route via the user inbox topic, not a channel topic,
     * so WebSocket subscriptions are not needed.
     */
    public List<UUID> listJoinedChannelIds(UUID userId) {
        List<UUID> allIds = memberRepository.findByIdUserId(userId).stream()
                .map(ChannelMember::getChannelId)
                .toList();
        return channelRepository.findAllById(allIds).stream()
                .filter(c -> c.getType() != ChannelType.DM)
                .map(Channel::getId)
                .toList();
    }

    /**
     * Sidebar list: joined PUBLIC/PRIVATE channels and DM channels, returned
     * separately. Both come from channel_members — DM channels are split out by
     * type.
     */
    public ChannelListDTO listChannelsForUser(UUID userId) {
        // Query 1: channel_members — all channel IDs + per-channel read position
        List<ChannelMember> members = memberRepository.findByIdUserId(userId);
        Map<UUID, UUID> lastReadById = new java.util.HashMap<>();
        members.forEach(m -> lastReadById.put(m.getChannelId(), m.getLastReadMessageId()));

        // Query 2: channels — metadata + lastMessageId, split DM vs non-DM
        List<UUID> allIds = members.stream().map(ChannelMember::getChannelId).toList();
        Map<Boolean, List<Channel>> byDm = channelRepository.findAllById(allIds).stream()
                .collect(Collectors.partitioningBy(c -> c.getType() == ChannelType.DM));

        // Query 3: dm_channels — bulk PK lookup to resolve otherUserId per DM
        List<UUID> dmIds = byDm.get(true).stream().map(Channel::getId).toList();
        Map<UUID, UUID> otherUserById = dmChannelRepository.findByChannelIdIn(dmIds).stream()
                .collect(Collectors.toMap(
                        DmChannel::getChannelId,
                        dm -> dm.getUserId1().equals(userId) ? dm.getUserId2() : dm.getUserId1()));

        // Query 4: users — batch fetch profiles for all DM participants
        List<UserDTO> dmUsers = userRepository.findAllById(otherUserById.values()).stream()
                .map(UserDTO::from)
                .toList();

        List<ChannelDTO> channels = byDm.get(false).stream()
                .map(c -> ChannelDTO.from(c, lastReadById.get(c.getId()), null))
                .toList();
        List<ChannelDTO> dms = byDm.get(true).stream()
                .map(c -> ChannelDTO.from(c, lastReadById.get(c.getId()), otherUserById.get(c.getId())))
                .toList();
        return new ChannelListDTO(channels, dms, dmUsers);
    }

    /** All member user IDs in a channel. */
    public List<UUID> listMembers(UUID channelId) {
        requireChannelExists(channelId);
        return memberRepository.findByIdChannelId(channelId).stream()
                .map(ChannelMember::getUserId)
                .toList();
    }

    /**
     * Find or create the PRIVATE channel between two users. Lookup is by the sorted
     * user pair in dm_channels — DB-level unique constraint prevents duplicates
     * even under concurrent requests.
     */
    @Transactional("mysqlTransactionManager")
    public ChannelDTO findOrCreateDm(UUID userA, UUID userB) {
        UUID uid1 = userA.compareTo(userB) <= 0 ? userA : userB;
        UUID uid2 = userA.compareTo(userB) <= 0 ? userB : userA;

        return dmChannelRepository.findByUserId1AndUserId2(uid1, uid2)
                .map(dm -> ChannelDTO.from(channelRepository.findById(dm.getChannelId())
                        .orElseThrow(() -> new NotFoundException("Channel not found"))))
                .orElseGet(() -> {
                    Channel channel = new Channel(null, ChannelType.DM);
                    channelRepository.save(channel);
                    dmChannelRepository.save(new DmChannel(channel.getId(), uid1, uid2));
                    memberRepository.save(new ChannelMember(channel.getId(), userA));
                    memberRepository.save(new ChannelMember(channel.getId(), userB));
                    return ChannelDTO.from(channel);
                });
    }

    /**
     * Validate that {@code userId} is a member of a DM channel and return the other
     * participant — single query replaces separate requireMember + findDmRecipient
     * calls.
     */
    public UUID requireMemberAndGetDmRecipient(UUID channelId, UUID userId) {
        List<UUID> members = memberRepository.findByIdChannelId(channelId).stream()
                .map(ChannelMember::getUserId)
                .toList();
        if (!members.contains(userId)) {
            throw new BadRequestException("Not a member of channel " + channelId);
        }
        return members.stream()
                .filter(id -> !id.equals(userId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Recipient not found in DM channel " + channelId));
    }

    /**
     * All PUBLIC channels with a per-caller {@code joined} flag. Used by the
     * Browse Channels modal so users can discover and join new channels.
     */
    public List<ChannelDTO> browsePublicChannels(UUID userId) {
        Set<UUID> joined = memberRepository.findByIdUserId(userId).stream()
                .map(ChannelMember::getChannelId)
                .collect(Collectors.toSet());
        return channelRepository.findByType(ChannelType.PUBLIC).stream()
                .map(c -> ChannelDTO.from(c, null, null, joined.contains(c.getId())))
                .toList();
    }

    /** Enforce channel membership — throws if the user is not a member. */
    public void requireMember(UUID channelId, UUID userId) {
        if (!memberRepository.existsByIdChannelIdAndIdUserId(channelId, userId)) {
            throw new BadRequestException("Not a member of channel " + channelId);
        }
    }

    /**
     * Return the channel type from DB — used where routing depends on the actual
     * type.
     */
    public ChannelType getChannelType(UUID channelId) {
        return channelRepository.findById(channelId)
                .map(Channel::getType)
                .orElseThrow(() -> new NotFoundException("Channel not found: " + channelId));
    }

    /** Update the last message ID on a channel after a new message is sent. */
    @Transactional("mysqlTransactionManager")
    public void updateLastMessageId(UUID channelId, UUID lastMessageId) {
        channelRepository.updateLastMessage(channelId, lastMessageId);
    }

    /** Upsert read position for a user in a channel. */
    @Transactional("mysqlTransactionManager")
    public void markRead(UUID channelId, UUID userId, UUID lastReadMessageId) {
        memberRepository.updateLastRead(channelId, userId, lastReadMessageId);
    }

    private void requireChannelExists(UUID channelId) {
        if (!channelRepository.existsById(channelId)) {
            throw new NotFoundException("Channel not found: " + channelId);
        }
    }
}
