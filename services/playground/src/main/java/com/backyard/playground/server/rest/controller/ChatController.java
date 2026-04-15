package com.backyard.playground.server.rest.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.backyard.playground.data.model.UserDTO;
import com.backyard.playground.data.model.chat.ChannelDTO;
import com.backyard.playground.data.model.chat.ChannelListDTO;
import com.backyard.playground.data.model.chat.ChatEventType;
import com.backyard.playground.data.persist.mysql.auth.User;
import com.backyard.playground.data.persist.mysql.auth.UserRepository;
import com.backyard.playground.data.persist.mysql.chat.Channel.ChannelType;
import com.backyard.playground.data.model.chat.CreateChannelInputDTO;
import com.backyard.playground.data.model.chat.EditMessageInputDTO;
import com.backyard.playground.data.model.chat.MessageDTO;
import com.backyard.playground.data.model.chat.ReplyDTO;
import com.backyard.playground.data.model.chat.SendMessageInputDTO;
import com.backyard.playground.data.model.chat.SendReplyInputDTO;
import com.backyard.playground.exception.ConflictException;
import com.backyard.playground.server.websocket.RedisPubService;
import com.backyard.playground.service.chat.ChannelService;
import com.backyard.playground.service.chat.ChatService;
import org.springframework.security.crypto.password.PasswordEncoder;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Chat REST API.
 *
 * Auth: {@code X-Mock-User-Id} header — caller supplies any UUID. The
 * {@code /api} prefix is added by {@code WebMvcConfig.addPathPrefix}.
 *
 * Send path (REST write, WebSocket receive): POST /messages or /replies
 * persists to Cassandra then publishes to Redis, which fans out to all
 * connected WebSocket sessions on all nodes.
 */
@Tag(name = "Chat")
@RestController
@RequestMapping("/{version}/chat")
public class ChatController {

    // Fixed password for demo user creation — see createDemoUser below.
    private static final String DEMO_PASSWORD = "backyard";

    private final ChannelService channelService;
    private final ChatService chatService;
    private final RedisPubService pubService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public ChatController(
            ChannelService channelService,
            ChatService chatService,
            RedisPubService pubService,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder) {
        this.channelService = channelService;
        this.chatService = chatService;
        this.pubService = pubService;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    // ── Demo user endpoints (chat login page, no real auth) ───────────────────

    /**
     * DEMO ONLY — not for production use.
     *
     * <p>Lists all users so the login page can render a one-click profile picker.
     */
    @Operation(summary = "List all users (demo login picker)")
    @GetMapping(value = "/users", version = "1+")
    public List<UserDTO> listAllUsers() {
        return userRepository.findAll().stream()
                .map(UserDTO::from)
                .toList();
    }

    /**
     * DEMO ONLY — not for production use.
     *
     * <p>Creates a user from a display name only, using a synthetic email and a
     * fixed shared password. Returns the new profile so the client can store the
     * ID and enter the app without a separate login step.
     */
    @Operation(summary = "Create a demo user (chat login page)")
    @PostMapping(value = "/users", version = "1+")
    @ResponseStatus(HttpStatus.CREATED)
    public UserDTO createDemoUser(@RequestBody DemoRegisterRequest req) {
        String email = req.name().toLowerCase().replaceAll("\\s+", ".") + "@backyard.com";
        if (userRepository.findByEmail(email).isPresent()) {
            throw new ConflictException("name already taken");
        }
        User user = new User(email, passwordEncoder.encode(DEMO_PASSWORD));
        user.setName(req.name());
        userRepository.save(user);
        return UserDTO.from(user);
    }

    /**
     * Batch fetch user profiles by ID — used by the chat client to resolve
     * sender names for a page of messages without embedding user objects in
     * every message response.
     */
    @Operation(summary = "Batch fetch user profiles")
    @PostMapping(value = "/users/batch", version = "1+")
    public List<UserDTO> getUsers(@RequestBody List<UUID> ids) {
        return userRepository.findAllById(ids).stream()
                .map(UserDTO::from)
                .toList();
    }

    public record DemoRegisterRequest(String name) {
    }

    // ── Channels ─────────────────────────────────────────────────────────────

    @Operation(summary = "List channels the caller has joined, and their DMs")
    @GetMapping(value = "/channels", version = "1+")
    public ChannelListDTO listChannels(
            @RequestHeader("X-Mock-User-Id") UUID userId) {
        return channelService.listChannelsForUser(userId);
    }

    @Operation(summary = "Browse all public channels (with joined flag)")
    @GetMapping(value = "/channels/browse", version = "1+")
    public List<ChannelDTO> browseChannels(
            @RequestHeader("X-Mock-User-Id") UUID userId) {
        return channelService.browsePublicChannels(userId);
    }

    @Operation(summary = "Create a channel")
    @PostMapping(value = "/channels", version = "1+")
    @ResponseStatus(HttpStatus.CREATED)
    public ChannelDTO createChannel(
            @RequestHeader("X-Mock-User-Id") UUID userId,
            @RequestBody CreateChannelInputDTO input) {
        ChannelDTO dto = channelService.createChannel(userId, input);
        pubService.publishUserControlEvent(userId, ChatEventType.CHANNEL_JOINED, dto);
        return dto;
    }

    @Operation(summary = "Open or retrieve a DM channel with another user")
    @PostMapping(value = "/dms", version = "1+")
    @ResponseStatus(HttpStatus.OK)
    public ChannelDTO openDm(
            @RequestHeader("X-Mock-User-Id") UUID userId,
            @RequestParam UUID recipientId) {
        return channelService.findOrCreateDm(userId, recipientId);
    }

    @Operation(summary = "Join a channel")
    @PostMapping(value = "/channels/{channelId}/join", version = "1+")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void joinChannel(
            @RequestHeader("X-Mock-User-Id") UUID userId,
            @PathVariable UUID channelId) {
        ChannelDTO dto = channelService.joinChannel(channelId, userId);
        pubService.publishUserControlEvent(userId, ChatEventType.CHANNEL_JOINED, dto);
    }

    @Operation(summary = "Leave a channel")
    @DeleteMapping(value = "/channels/{channelId}/leave", version = "1+")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leaveChannel(
            @RequestHeader("X-Mock-User-Id") UUID userId,
            @PathVariable UUID channelId) {
        channelService.leaveChannel(channelId, userId);
        pubService.publishUserControlEvent(userId, ChatEventType.CHANNEL_LEFT, ChannelDTO.withId(channelId));
    }

    @Operation(summary = "List members of a channel")
    @GetMapping(value = "/channels/{channelId}/members", version = "1+")
    public List<UUID> listMembers(
            @RequestHeader("X-Mock-User-Id") UUID userId,
            @PathVariable UUID channelId) {
        channelService.requireMember(channelId, userId);
        return channelService.listMembers(channelId);
    }

    // ── Messages ──────────────────────────────────────────────────────────────

    @Operation(summary = "Load paginated message history for a channel")
    @GetMapping(value = "/channels/{channelId}/messages", version = "1+")
    public List<MessageDTO> getMessages(
            @RequestHeader("X-Mock-User-Id") UUID userId,
            @PathVariable UUID channelId,
            @RequestParam(required = false) UUID before,
            @RequestParam(defaultValue = "50") int limit) {
        channelService.requireMember(channelId, userId);
        return chatService.getMessages(channelId, before, limit);
    }

    @Operation(summary = "Send a message to a channel")
    @PostMapping(value = "/channels/{channelId}/messages", version = "1+")
    @ResponseStatus(HttpStatus.CREATED)
    public MessageDTO sendMessage(
            @RequestHeader("X-Mock-User-Id") UUID userId,
            @PathVariable UUID channelId,
            @RequestBody SendMessageInputDTO input) {
        ChannelType type = channelService.getChannelType(channelId);
        if (type == ChannelType.DM) {
            UUID recipientId = channelService.requireMemberAndGetDmRecipient(channelId, userId);
            MessageDTO dto = chatService.sendMessage(channelId, userId, input);
            pubService.publishDmEvent(recipientId, ChatEventType.MESSAGE_CREATED, dto);
            return dto;
        }
        channelService.requireMember(channelId, userId);
        MessageDTO dto = chatService.sendMessage(channelId, userId, input);
        pubService.publishChannelEvent(channelId, ChatEventType.MESSAGE_CREATED, dto);
        return dto;
    }

    @Operation(summary = "Edit a message body")
    @PatchMapping(value = "/channels/{channelId}/messages/{messageId}", version = "1+")
    public MessageDTO editMessage(
            @RequestHeader("X-Mock-User-Id") UUID userId,
            @PathVariable UUID channelId,
            @PathVariable UUID messageId,
            @RequestBody EditMessageInputDTO input) {
        channelService.requireMember(channelId, userId);
        MessageDTO dto = chatService.editMessage(channelId, messageId, userId, input);
        pubService.publishChannelEvent(channelId, ChatEventType.MESSAGE_EDITED, dto);
        return dto;
    }

    @Operation(summary = "Soft-delete a message")
    @DeleteMapping(value = "/channels/{channelId}/messages/{messageId}", version = "1+")
    public MessageDTO deleteMessage(
            @RequestHeader("X-Mock-User-Id") UUID userId,
            @PathVariable UUID channelId,
            @PathVariable UUID messageId) {
        channelService.requireMember(channelId, userId);
        MessageDTO dto = chatService.deleteMessage(channelId, messageId, userId);
        pubService.publishChannelEvent(channelId, ChatEventType.MESSAGE_DELETED, dto);
        return dto;
    }

    // ── Threads ───────────────────────────────────────────────────────────────

    @Operation(summary = "Send a thread reply")
    @PostMapping(value = "/messages/{parentId}/replies", version = "1+")
    @ResponseStatus(HttpStatus.CREATED)
    public ReplyDTO sendReply(
            @RequestHeader("X-Mock-User-Id") UUID userId,
            @PathVariable UUID parentId,
            @RequestParam UUID parentChannelId,
            @RequestBody SendReplyInputDTO input) {
        channelService.requireMember(parentChannelId, userId);
        // TODO: publish reply to each thread follower's user inbox once
        // thread_followers table is in place. Sender gets the reply from
        // the 201 response; followers will be notified via publishDmEvent.
        return chatService.sendReply(parentChannelId, parentId, userId, input);
    }

    @Operation(summary = "Edit a reply body")
    @PatchMapping(value = "/messages/{parentId}/replies/{replyId}", version = "1+")
    public ReplyDTO editReply(
            @RequestHeader("X-Mock-User-Id") UUID userId,
            @PathVariable UUID parentId,
            @PathVariable UUID replyId,
            @RequestBody EditMessageInputDTO input) {
        return chatService.editReply(parentId, replyId, userId, input);
    }

    @Operation(summary = "Soft-delete a reply")
    @DeleteMapping(value = "/messages/{parentId}/replies/{replyId}", version = "1+")
    public ReplyDTO deleteReply(
            @RequestHeader("X-Mock-User-Id") UUID userId,
            @PathVariable UUID parentId,
            @PathVariable UUID replyId) {
        return chatService.deleteReply(parentId, replyId, userId);
    }

    @Operation(summary = "Load thread replies")
    @GetMapping(value = "/messages/{parentId}/replies", version = "1+")
    public List<ReplyDTO> getReplies(
            @RequestHeader("X-Mock-User-Id") UUID userId,
            @PathVariable UUID parentId,
            @RequestParam UUID parentChannelId,
            @RequestParam(required = false) UUID after,
            @RequestParam(defaultValue = "50") int limit) {
        channelService.requireMember(parentChannelId, userId);
        return chatService.getReplies(parentId, after, limit);
    }

    // ── Read state ────────────────────────────────────────────────────────────

    @Operation(summary = "Mark a channel as read")
    @PostMapping(value = "/channels/{channelId}/read", version = "1+")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(
            @RequestHeader("X-Mock-User-Id") UUID userId,
            @PathVariable UUID channelId,
            @RequestParam UUID lastReadMessageId) {
        chatService.markRead(channelId, userId, lastReadMessageId);
    }

}
