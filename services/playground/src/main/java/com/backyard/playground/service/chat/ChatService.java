package com.backyard.playground.service.chat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.backyard.playground.data.model.chat.EditMessageInputDTO;
import com.backyard.playground.exception.BadRequestException;
import com.backyard.playground.exception.ForbiddenException;
import com.backyard.playground.exception.NotFoundException;
import com.backyard.playground.data.model.chat.MessageDTO;
import com.backyard.playground.data.model.chat.ReplyDTO;
import com.backyard.playground.data.model.chat.SendMessageInputDTO;
import com.backyard.playground.data.model.chat.SendReplyInputDTO;
import com.backyard.playground.data.persist.cassandra.chat.entity.MessageByChannel;
import com.backyard.playground.data.persist.cassandra.chat.entity.ReplyByMessage;
import com.backyard.playground.data.persist.cassandra.chat.repository.MessagesByChannelRepository;
import com.backyard.playground.data.persist.cassandra.chat.repository.RepliesByMessageRepository;
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;

@Service
public class ChatService {

    private static final int DEFAULT_PAGE_SIZE = 50;

    /**
     * UUID v7 generator — thread-safe, reuse a single instance. High 48 bits = ms
     * timestamp; sort order = time order.
     */
    private static final TimeBasedEpochGenerator UUID_V7 = Generators.timeBasedEpochGenerator();

    private final MessagesByChannelRepository messageRepo;
    private final RepliesByMessageRepository replyRepo;
    private final ChannelService channelService;

    public ChatService(
            MessagesByChannelRepository messageRepo,
            RepliesByMessageRepository replyRepo,
            ChannelService channelService) {
        this.messageRepo = messageRepo;
        this.replyRepo = replyRepo;
        this.channelService = channelService;
    }

    /** Send a top-level message. Returns the persisted DTO for broadcast. */
    public MessageDTO sendMessage(UUID channelId, UUID senderId, SendMessageInputDTO input) {
        UUID messageId = UUID_V7.generate();

        MessageByChannel entity = new MessageByChannel();
        entity.setChannelId(channelId);
        entity.setMessageId(messageId);
        entity.setSenderId(senderId);
        entity.setBody(input.getBody());
        entity.setDeleted(false);
        entity.setEdited(false);
        entity.setHasThread(false);
        messageRepo.save(entity);

        channelService.updateLastMessageId(channelId, messageId);

        return MessageDTO.from(entity);
    }

    /** Edit a message body. Only the original sender may edit. */
    public MessageDTO editMessage(UUID channelId, UUID messageId, UUID userId,
            EditMessageInputDTO input) {
        requireMessageAuthor(channelId, messageId, userId);
        messageRepo.updateBody(channelId, messageId, input.getBody());

        MessageDTO dto = new MessageDTO();
        dto.setChannelId(channelId);
        dto.setMessageId(messageId);
        dto.setBody(input.getBody());
        return dto;
    }

    /** Soft-delete a message. Only the original sender may delete. */
    public MessageDTO deleteMessage(UUID channelId, UUID messageId, UUID userId) {
        requireMessageAuthor(channelId, messageId, userId);
        messageRepo.markDeleted(channelId, messageId);

        MessageDTO dto = new MessageDTO();
        dto.setChannelId(channelId);
        dto.setMessageId(messageId);
        return dto;
    }

    /**
     * Load a page of messages for a channel feed. Cursor is the message_id (UUID
     * v7) of the oldest message on the current page — no bucket or timestamp
     * needed.
     */
    public List<MessageDTO> getMessages(UUID channelId, UUID beforeId, int limit) {
        if (limit <= 0 || limit > 100)
            limit = DEFAULT_PAGE_SIZE;
        List<MessageByChannel> rows = beforeId == null
                ? messageRepo.findPage(channelId, limit)
                : messageRepo.findPageBefore(channelId, beforeId, limit);
        // Cassandra returns newest-first (DESC clustering); reverse to oldest-first
        // so the feed renders chronologically top-to-bottom.
        List<MessageByChannel> ordered = new ArrayList<>(rows);
        Collections.reverse(ordered);
        return ordered.stream().map(MessageDTO::from).toList();
    }

    /** Send a thread reply. Validates that parentId belongs to parentChannelId. */
    public ReplyDTO sendReply(UUID parentChannelId, UUID parentId, UUID senderId, SendReplyInputDTO input) {
        MessageByChannel parent = messageRepo.findByChannelIdAndMessageId(parentChannelId, parentId)
                .orElseThrow(() -> new BadRequestException(
                        "Message " + parentId + " not found in channel " + parentChannelId));
        // requireParentExists reused by editReply/deleteReply; sendReply fetches the
        // full entity anyway (to check hasThread), so we validate inline here.
        UUID messageId = UUID_V7.generate();

        ReplyByMessage entity = new ReplyByMessage();
        entity.setParentId(parentId);
        entity.setMessageId(messageId);
        entity.setSenderId(senderId);
        entity.setChannelId(parentChannelId);
        entity.setBody(input.getBody());
        entity.setDeleted(false);
        replyRepo.save(entity);

        // Only write has_thread on the first reply — reuse the already-fetched parent
        // to avoid a redundant Cassandra write on every subsequent reply.
        if (!parent.isHasThread()) {
            messageRepo.markHasThread(parentChannelId, parentId);
        }

        return ReplyDTO.from(entity);
    }

    /** Edit a reply body. Only the original sender may edit. */
    public ReplyDTO editReply(UUID parentId, UUID messageId, UUID userId, EditMessageInputDTO input) {
        ReplyByMessage reply = requireReplyAuthor(parentId, messageId, userId);
        replyRepo.updateBody(parentId, messageId, input.getBody());

        ReplyDTO dto = new ReplyDTO();
        dto.setParentId(parentId);
        dto.setMessageId(messageId);
        dto.setChannelId(reply.getChannelId());
        dto.setSenderId(userId);
        dto.setBody(input.getBody());
        return dto;
    }

    /** Soft-delete a reply. Only the original sender may delete. */
    public ReplyDTO deleteReply(UUID parentId, UUID messageId, UUID userId) {
        ReplyByMessage reply = requireReplyAuthor(parentId, messageId, userId);
        replyRepo.markDeleted(parentId, messageId);

        ReplyDTO dto = new ReplyDTO();
        dto.setParentId(parentId);
        dto.setMessageId(messageId);
        dto.setChannelId(reply.getChannelId());
        dto.setSenderId(userId);
        return dto;
    }

    private void requireMessageAuthor(UUID channelId, UUID messageId, UUID userId) {
        UUID senderId = messageRepo.findByChannelIdAndMessageId(channelId, messageId)
                .orElseThrow(() -> new NotFoundException("Message not found: " + messageId))
                .getSenderId();
        if (!senderId.equals(userId)) {
            throw new ForbiddenException("Only the author may modify this message");
        }
    }

    /**
     * Fetch the reply, verify the caller is the author, and return the entity
     * so callers can reuse already-loaded fields (e.g. channelId) without a
     * second query.
     */
    private ReplyByMessage requireReplyAuthor(UUID parentId, UUID messageId, UUID userId) {
        ReplyByMessage reply = replyRepo.findByParentIdAndMessageId(parentId, messageId)
                .orElseThrow(() -> new NotFoundException("Reply not found: " + messageId));
        if (!userId.equals(reply.getSenderId())) {
            throw new ForbiddenException("Only the author may modify this reply");
        }
        return reply;
    }

    /** Load thread replies for a parent message. */
    public List<ReplyDTO> getReplies(UUID parentId, UUID afterId, int limit) {
        if (limit <= 0 || limit > 100)
            limit = DEFAULT_PAGE_SIZE;
        List<ReplyByMessage> rows = afterId == null
                ? replyRepo.findReplies(parentId, limit)
                : replyRepo.findRepliesAfter(parentId, afterId, limit);
        return rows.stream().map(ReplyDTO::from).toList();
    }

    /** Upsert read position for a user in a channel. */
    public void markRead(UUID channelId, UUID userId, UUID lastReadMessageId) {
        channelService.markRead(channelId, userId, lastReadMessageId);
    }

}
