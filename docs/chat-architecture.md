# Chat System Architecture

A Slack-like real-time chat system with public channels, direct messages, and
threaded replies. Implemented inside the `playground` Spring Boot service
(Java 21, Spring Boot 4.x).

---

## Overview

The system is built around one core principle borrowed from Slack and Discord:
**REST for writes, WebSocket for receives**. Clients send messages via HTTP POST
and receive all pushed events over a persistent WebSocket connection. This gives
every write an immediate HTTP acknowledgment (message ID, timestamp, error codes)
while keeping the real-time delivery path simple and stateless on the write side.

---

## Storage

### Why two databases?

Chat has two fundamentally different data shapes that favour opposite storage
engines:

| Data | Shape | Access pattern |
|------|-------|----------------|
| Channel roster, membership | Relational, small | Bidirectional joins, low volume |
| Messages, replies | Append-only, unbounded | Sequential scans, high write volume |

Trying to put messages in MySQL creates unbounded table growth and painful range
queries. Trying to put channel membership in Cassandra requires modelling every
query direction as a separate table and loses foreign-key semantics. The split
lets each engine do what it is good at.

---

### MySQL — channel roster and membership

**Tables: `channels`, `channel_members`, `dm_channels`**

MySQL holds the relational, low-volume metadata that needs bidirectional queries
and transactional consistency:

- "What channels has user X joined?" (`WHERE user_id = ?` on `channel_members`)
- "Who are the members of channel Y?" (`WHERE channel_id = ?` on `channel_members`)
- "Does a DM already exist between user A and B?" (unique constraint on
  `dm_channels (user_id_1, user_id_2)`)
- "What is the most recent message in this channel?" (`last_message_id` on
  `channels`)

A single `channel_members` table serves both directions with one composite PK
`(channel_id, user_id)` plus a secondary index on `user_id`. There is no
need to maintain a second denormalised table.

**Read position** is stored as `last_read_message_id` on `channel_members` — one
column on an already-fetched row. The alternative (a Cassandra `user_last_read`
table) was dropped because: (a) read positions are low-volume and relational by
nature, (b) it avoided an extra round-trip to a separate Cassandra keyspace on
every channel list load, and (c) the comparison `last_read_message_id <
last_message_id` is a simple UUID comparison since both are UUID v7.

**`last_message_id` instead of `last_message_at`** — the original design used a
`last_message_at` timestamp + `last_message_bucket` string to resolve Cassandra
pagination. The final implementation uses a single `last_message_id` UUID v7
field instead. UUID v7 encodes the creation timestamp in its high 48 bits, so
timestamp ordering and bucket derivation are both available from one column
without storing them separately.

```sql
CREATE TABLE channels (
    id               BINARY(16) PRIMARY KEY,   -- UUID v7
    name             VARCHAR(128),
    type             ENUM('PUBLIC','PRIVATE','DM') NOT NULL,
    created_at       DATETIME(3),
    last_message_id  BINARY(16)                -- UUID v7; high 48 bits = Unix ms
);

CREATE TABLE channel_members (
    channel_id          BINARY(16),
    user_id             BINARY(16),
    joined_at           DATETIME(3),
    last_read_message_id BINARY(16),           -- UUID v7; null until first open
    PRIMARY KEY (channel_id, user_id),
    INDEX idx_channel_members_user_id (user_id)
);

CREATE TABLE dm_channels (
    channel_id  BINARY(16) PRIMARY KEY,
    user_id_1   BINARY(16) NOT NULL,           -- lower UUID of the pair
    user_id_2   BINARY(16) NOT NULL,           -- higher UUID of the pair
    UNIQUE KEY uq_dm_pair (user_id_1, user_id_2)
);
```

The `dm_channels` pair is stored with the lower UUID first (sorted by
`compareTo`) so the unique constraint reliably prevents duplicate DM channels
regardless of which user initiates the conversation.

---

### Cassandra keyspace `chat` — messages and replies

Cassandra is chosen for messages because:

- **Write volume** — even at moderate scale, thousands of messages per second
  is a normal load for a chat system. Cassandra's log-structured merge tree
  handles high append rates without locking.
- **Partition-local queries** — every message query is scoped to a channel.
  There is never a need to scan across channels. This maps perfectly to
  Cassandra's partition model.
- **No cross-row transactions needed** — sending a message is a single insert;
  edits and deletes update one row. No multi-row atomicity required.
- **Horizontal scaling** — Cassandra shards data across nodes automatically by
  partition key, with no manual sharding required.

```cql
CREATE KEYSPACE IF NOT EXISTS chat
    WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 3};

USE chat;

CREATE TABLE messages_by_channel (
    channel_id  UUID,
    message_id  UUID,       -- UUID v7 (time-ordered); partition key is channel only
    sender_id   UUID,
    body        TEXT,
    deleted     BOOLEAN,
    edited      BOOLEAN,
    has_thread  BOOLEAN,
    PRIMARY KEY (channel_id, message_id)
) WITH CLUSTERING ORDER BY (message_id DESC);

CREATE TABLE replies_by_message (
    parent_id   UUID,
    message_id  UUID,       -- UUID v7
    channel_id  UUID,       -- denormalised for channel membership check on reply ops
    sender_id   UUID,
    body        TEXT,
    deleted     BOOLEAN,
    PRIMARY KEY (parent_id, message_id)
) WITH CLUSTERING ORDER BY (message_id ASC);
```

#### Key Cassandra design decisions

**No date bucket in the partition key** — the original plan bucketed messages by
`(channel_id, 'YYYY-MM-DD')` to bound partition size. The final design uses
`channel_id` alone as the partition key. The bucket was removed for two reasons:
(1) UUID v7 as the clustering key already provides time-ordered pagination
without needing a date component, and (2) eliminating the bucket removes the
multi-partition scan required when paginating across day boundaries — the cursor
is purely `message_id < ?` on a single partition.

At very high volume, partition size can become a concern (see Scaling section
below) but for typical channels a single partition is simpler and faster.

**UUID v7 as the sole cursor** — rather than a composite cursor of
`(created_at_ms, message_id)`, the pagination cursor is simply the `message_id`
UUID v7 itself. Since the high 48 bits of UUID v7 encode the Unix millisecond
timestamp, `message_id < ?` is equivalent to `created_at < ?` with the
`message_id` as a tiebreaker for same-millisecond messages. This gives the client
a single opaque token to hold instead of two fields.

**Soft delete only** — `deleted = true` is set instead of removing rows. Hard
deletes in Cassandra produce tombstones that accumulate in compaction and degrade
range scan performance. Soft deletes are invisible to the client (rendered as
"message deleted") while keeping the storage layer healthy.

**`has_thread` one-way flag** — set to `true` on the first reply, never unset.
This lets the feed render a "thread" indicator without querying the
`replies_by_message` partition for every visible message. A more accurate count
would require a `SELECT COUNT(*)` per visible message — unacceptable for a 50-
message page render.

**`ALLOW FILTERING` on single-message lookup** — `findByChannelIdAndMessageId`
uses `ALLOW FILTERING`. This is safe because the query is bounded to a single
partition (`channel_id = ?`); filtering happens only within that partition's
rows, not across the full table. Cassandra's warning about `ALLOW FILTERING`
applies to unbounded cross-partition scans, not to within-partition filtering.

**`channel_id` denormalised on `replies_by_message`** — the reply entity stores
`channel_id` so that edit/delete operations can verify channel membership without
fetching the parent message first.

**Schema migrations — never backfill added columns** — Cassandra's `ALTER TABLE
ADD` adds a column with no on-disk representation for existing rows; those rows
read back as `null` for the new column. This is intentional and cheap. If you
backfill existing rows (e.g. `UPDATE ... SET edited = false`), Cassandra writes a
new cell for every row touched, inflating SSTables with data that is semantically
identical to `null`. The correct pattern is:

1. Add the column via a migration (`ALTER TABLE ... ADD col type`).
2. Handle `null` in the application layer (e.g. treat `null` boolean as `false`).
3. Only new writes set an explicit value.

Corollary: new columns should always be nullable (no `NOT NULL` equivalent exists
in CQL) and the application must tolerate `null` for any column added after the
initial schema was deployed.

---

## Real-Time Transport

### Why raw WebSocket instead of STOMP

The original plan used STOMP over WebSocket with Spring's in-process message
broker. The final implementation uses raw WebSocket (`TextWebSocketHandler`) for
these reasons:

1. **STOMP adds unnecessary protocol overhead** — STOMP is a text-framed
   sub-protocol with `SUBSCRIBE`, `SEND`, `MESSAGE` verbs. For a receive-only
   client that never sends frames back (all sends go through REST), STOMP adds
   round-trip subscription handshakes and frame parsing with no benefit.

2. **Direct Redis→socket routing** — with raw WebSocket the server can push the
   Redis message body directly to sockets as a `TextMessage` with zero
   re-serialisation. STOMP would require wrapping the JSON payload in a STOMP
   `MESSAGE` frame, adding unnecessary overhead.

3. **Explicit subscription control** — the handler owns two maps
   (`sessionsByTopic` and `topicsBySession`) that give precise control over
   which sessions receive which events and when. The Spring STOMP broker uses its
   own internal subscription registry which is harder to hook into for dynamic
   Redis subscription lifecycle management.

4. **No SockJS fallback needed** — the clients are modern browsers. Native
   WebSocket is universally supported. SockJS (long-poll, iframe) adds
   complexity with no benefit.

### WebSocket endpoint

```
ws://{host}/ws?userId={uuid}
```

The `userId` is passed as a query parameter because the browser's native
`WebSocket` API does not support custom request headers. REST endpoints still
use the `X-Mock-User-Id` header — only the WebSocket handshake uses the query
parameter. A `HandshakeInterceptor` reads it and stores it in the session
attributes map under the key `X-Mock-User-Id` so downstream code uses a single
constant for both paths.

### On-connect auto-subscription

When a WebSocket session is established the server immediately subscribes it to
all relevant Redis topics — no client-side SUBSCRIBE messages required:

1. `chat.user.{userId}` — the user's **personal inbox**. Receives DM messages
   and control events (channel joined/left).
2. `chat.channel.{channelId}` — one per PUBLIC/PRIVATE channel the user has
   joined at connect time.

DM channels are intentionally excluded from step 2. DM messages are published
to the recipient's personal inbox (`chat.user.{recipientId}`) rather than a
channel topic, so no channel-level subscription is needed for DMs.

### Distributed subscription management (control events)

A problem arises in a multi-node deployment: when a user joins or leaves a
channel via REST, the REST handler runs on one node but the user's WebSocket
session lives on a different node. The REST node cannot directly call
`addSubscription` on the other node.

The solution: publish a **control event** to `chat.user.{userId}` (the personal
inbox) on Redis. Every node subscribed to that user's inbox topic (i.e., the
node holding the user's WebSocket) will receive the event and update its local
subscription maps accordingly.

```
User calls POST /channels/{id}/join
  → REST handler: channelService.joinChannel(...)
  → pubService.publishUserControlEvent(userId, CHANNEL_JOINED, channelDTO)
  → Redis PUBLISH chat.user.{userId} { type: CHANNEL_JOINED, channel: {...} }
  → Node holding user's WS receives event
  → addSubscription(session, "chat.channel." + channelId)
  → Forward event to client so sidebar refreshes
```

`CHANNEL_LEFT` mirrors this in reverse: the node removes the subscription and
the client removes the channel from its sidebar.

### Redis pub/sub topics

| Topic | Publisher | Consumers |
|-------|-----------|-----------|
| `chat.channel.{channelId}` | REST node on message send/edit/delete | All nodes with a subscriber to that channel |
| `chat.user.{userId}` | REST node on DM send, join, leave | Node holding the user's WebSocket |

### Sender deduplication

When the sender posts a message via REST, they receive the created `MessageDTO`
in the 201 response and render it immediately. The Redis pub/sub broadcast would
then deliver the same message back to the sender's own WebSocket session,
producing a duplicate.

The server resolves this by comparing the event's `senderId` against the
`X-Mock-User-Id` attribute stored in each session. For `MESSAGE_CREATED` events,
the sender's session is skipped during delivery. Other event types (edit, delete,
channel control) are delivered to all sessions including the sender's.

### Subscription data structures

The handler maintains two maps on each node:

```java
ConcurrentHashMap<String, Set<WebSocketSession>> sessionsByTopic
    // topic → sessions subscribed to it
    // used to route incoming Redis messages to the right sockets

ConcurrentHashMap<String, Set<String>> topicsBySession
    // sessionId → topics it subscribed to
    // used to clean up all subscriptions on disconnect in O(topics this
    // session subscribed to) rather than O(all active topics on this node)
```

Redis subscriptions are managed dynamically:
- First local subscriber for a topic → subscribe this node to Redis
- Last local subscriber leaves → unsubscribe from Redis, remove map entry

---

## Redis Pub/Sub Service

### Why Redis

Redis pub/sub was chosen as the inter-node relay for three reasons:

1. **Already in the stack** — the playground service uses Redis for other
   purposes. Adding pub/sub is zero infrastructure cost.
2. **Fire-and-forget semantics match chat delivery** — a chat message that
   misses a subscriber because their node was down is acceptable (they will
   load history on reconnect). Redis pub/sub makes no durability guarantees,
   which is exactly right here. A durable broker (Kafka, RabbitMQ) would add
   complexity with no benefit for this delivery model.
3. **Low latency** — Redis pub/sub delivery is typically sub-millisecond within
   a datacenter. Users expect near-instant message delivery.

### Implementation

The service is split into an interface and two implementations selected by
Spring profile:

| Class | Profile | Behaviour |
|-------|---------|-----------|
| `RedisPubServiceImpl` | all except `home` | Serialises the `ChatEvent` to JSON and calls `redisTemplate.convertAndSend(topic, json)` |
| `RedisPubServiceNoOp` | `home` | Logs the event at DEBUG and drops it — Redis not available in the home profile |

This lets the full chat flow work locally without Redis by using the `home`
profile. WebSocket events simply don't fan out to other nodes, which is fine for
single-node local development.

### Publish side (`RedisPubServiceImpl`)

Three publish methods map to three routing strategies:

```java
// Channel message (public/private channels) — delivered to all subscribers
publishChannelEvent(channelId, type, messageDTO)
    → PUBLISH chat.channel.{channelId} { type, message, channel: null }

// DM message — delivered only to the recipient's inbox
publishDmEvent(recipientId, type, messageDTO)
    → PUBLISH chat.user.{recipientId} { type, message, channel: null }

// Control event (join/leave) — delivered to the user's inbox
publishUserControlEvent(userId, type, channelDTO)
    → PUBLISH chat.user.{userId} { type, message: null, channel }
```

All three serialise a `ChatEvent` record to JSON using Jackson and call
`StringRedisTemplate.convertAndSend`. The template is backed by Lettuce, which
uses a single async connection pool to Redis.

### Subscribe side (`ChatWebSocketHandler`)

Each node registers a single shared `MessageListener` instance with
`RedisMessageListenerContainer`. The container manages the Redis subscribe/
unsubscribe commands and dispatches incoming messages on a background thread.

```java
private final MessageListener redisListener = (message, pattern) ->
    onRedisMessage(
        new String(message.getBody(), UTF_8),   // JSON payload
        new String(message.getChannel(), UTF_8) // Redis topic name
    );
```

A **single listener instance** is reused across all topic registrations. This
is intentional: `removeMessageListener` matches by object reference, so reusing
one instance means a single `remove` call correctly unregisters from all topics
that listener was added to.

`onRedisMessage` branches on event type:

```
CHANNEL_JOINED / CHANNEL_LEFT (arrive on chat.user.{userId}):
  1. Look up user's local WebSocket sessions from sessionsByTopic
  2. Add or remove the channel subscription for each session
  3. Forward the event JSON to the client so the sidebar refreshes

MESSAGE_* (arrive on chat.channel.{id} or chat.user.{id}):
  1. Look up subscribed sessions for this topic
  2. Skip sender's session for MESSAGE_CREATED (already has it from REST 201)
  3. Write raw JSON TextMessage to each open session
```

### Redis topic naming

| Topic pattern | Used for |
|---------------|----------|
| `chat.channel.{channelId}` | Public/private channel message events |
| `chat.user.{userId}` | DM messages + channel control events |

The personal inbox (`chat.user.{userId}`) serves double duty: it carries both
DM message events and CHANNEL_JOINED/CHANNEL_LEFT control events. Using one
topic for both avoids a separate subscription per event category while keeping
user-specific events isolated from channel-wide events.

---

## Redis vs Kafka — When to Switch

Redis pub/sub and Kafka solve the same inter-node fan-out problem with different
trade-off profiles. The current implementation uses Redis; below is a precise
comparison to guide a future migration decision.

### Redis pub/sub (current)

| Property | Behaviour |
|----------|-----------|
| Delivery guarantee | At-most-once. A node that is down or slow misses events permanently. |
| Persistence | None. Events exist only while in-flight. |
| Consumer model | All subscribers to a topic receive every message (broadcast). |
| Latency | Sub-millisecond within a datacenter. |
| Operational cost | Zero extra infrastructure if Redis is already in the stack. |
| Backpressure | None. A slow consumer falls behind silently; events are dropped. |

This is correct for WebSocket fan-out: if a node is down, its clients are
disconnected and will reconnect to another node and reload history via REST.
The missed pub/sub events are never needed.

### Kafka (migration path)

| Property | Behaviour |
|----------|-----------|
| Delivery guarantee | At-least-once (with acknowledgement). Events survive broker restarts. |
| Persistence | Events retained on disk for a configurable retention window. |
| Consumer model | Consumer group — each partition delivered to exactly one member of the group. |
| Latency | Low single-digit milliseconds, higher than Redis. |
| Operational cost | Separate Kafka cluster (ZooKeeper or KRaft). |
| Backpressure | Built-in. Consumers pull at their own rate; lag is observable. |

### Consumer group per WebSocket node

With Kafka, the correct topology is **one consumer group per WebSocket node**,
not one shared consumer group across all nodes:

```
chat-events topic (partitioned by channelId)
  ├── Node A consumer group "ws-node-a"  → receives ALL partitions
  ├── Node B consumer group "ws-node-b"  → receives ALL partitions
  └── Node C consumer group "ws-node-c"  → receives ALL partitions
```

Each node needs to see every event for every channel that has a local subscriber.
If all nodes shared one consumer group, Kafka would assign each partition to only
one node — most events would be delivered to the wrong node and never reach the
WebSocket sessions that need them.

By giving each node its own consumer group name (e.g. `ws-node-{instanceId}`),
every node independently consumes all partitions. This is broadcast semantics
on top of Kafka's point-to-point consumer model.

**Optimisation**: A node only needs events for channels where it has local
subscribers. At large scale, nodes can dynamically seek/pause partitions for
channels with no local subscribers, reducing CPU spent deserialising irrelevant
events. This is an advanced optimisation — start with full consumption.

### When to switch from Redis to Kafka

Switch when any of these become true:

- Redis pub/sub throughput is saturating (typically >100k events/sec on a single
  instance) and topic sharding is too operationally complex
- You need event replay — e.g. a new node joining mid-deployment needs to catch
  up on in-flight events rather than serving stale history
- You need auditable event history (compliance, analytics pipelines)
- You are already running Kafka for other services and want a single event bus

For most chat deployments at moderate scale, Redis with topic sharding
(see Scaling section) is simpler and sufficient.

---

## Message Send Flow

### Top-level message (public channel)

```
Client → POST /api/v1/chat/channels/{channelId}/messages
Header: X-Mock-User-Id: {senderUUID}
Body:   { "body": "hello" }

1. Generate messageId (UUID v7)
2. Cassandra INSERT INTO messages_by_channel
3. MySQL UPDATE channels SET last_message_id = ?
4. Redis PUBLISH chat.channel.{channelId} { type: MESSAGE_CREATED, message: {...} }
   → every subscribed node fans out to local WebSocket sessions
   → sender's own session is skipped
5. Return 201 with MessageDTO to caller
```

### Direct message

```
Client → POST /api/v1/chat/channels/{dmChannelId}/messages
Header: X-Mock-User-Id: {senderUUID}

1-3. Same as above
4. Redis PUBLISH chat.user.{recipientId} { type: MESSAGE_CREATED, message: {...} }
   → delivered through recipient's personal inbox, not a channel topic
5. Return 201 with MessageDTO
```

DM events publish to the recipient's inbox rather than the channel topic because
DM channels are not subscribed at the channel level (see On-connect section).
This also means a new DM arriving from a user you have never messaged before will
be received correctly — the personal inbox subscription is always active.

If the DM channel is brand new (never seen before by the client), the client's
`MESSAGE_CREATED` handler detects that the channel ID is not in local state and
calls `GET /api/v1/chat/channels` to reload the sidebar, making the new DM
appear.

### Thread reply

```
Client → POST /api/v1/chat/messages/{parentId}/replies
Params: parentChannelId={uuid}
Body:   { "body": "..." }

1. Generate messageId (UUID v7)
2. Load parent to validate it belongs to parentChannelId
3. Cassandra INSERT INTO replies_by_message
4. If parent.hasThread == false: Cassandra UPDATE SET has_thread = true
   (only on first reply — avoids a redundant write on every subsequent reply)
5. Return 201 with ReplyDTO
```

Thread replies are not currently published via WebSocket. The infrastructure
(personal inbox per user) is in place for future thread-follower notifications.

### Edit and soft delete

Both use `PATCH` and `DELETE` respectively. The Cassandra update targets the row
by `(channel_id, message_id)`. After persisting, a Redis event is published to
the channel topic so other connected clients can patch their local render.

Only the original sender may edit or delete their own messages — enforced
server-side by loading the message and comparing `sender_id`.

---

## Pagination

```
GET /api/v1/chat/channels/{channelId}/messages?before={messageId}&limit=50
```

The cursor is the UUID v7 `messageId` of the oldest message on the current page.
The CQL query is:

```cql
SELECT * FROM chat.messages_by_channel
WHERE channel_id = ?
  AND message_id < ?   -- UUID v7 comparison = time comparison
LIMIT ?
```

Cassandra's `DESC` clustering order returns newest-first. The service reverses
the list before returning it so the client always receives messages in
oldest-to-newest (chronological) order.

For the initial load (no cursor), `findPage` is used without the `< ?`
predicate, returning the most recent messages.

---

## Unread State

The unread indicator is a boolean comparison using UUID v7 ordering:

```
unread = (channel.lastMessageId != null)
      && (member.lastReadMessageId == null
          || member.lastReadMessageId.compareTo(channel.lastMessageId) < 0)
```

`lastReadMessageId` is updated in two places:

1. **On channel switch** — `markCurrentChannelRead()` is called at the start of
   `selectChannel()`, recording the last visible message of the channel being
   left.

2. **On page unload** — a `beforeunload` listener calls the same function.
   It uses `fetch` with `keepalive: true` rather than `navigator.sendBeacon`
   because `sendBeacon` cannot set custom request headers and the server requires
   `X-Mock-User-Id`. `keepalive: true` achieves the same fire-and-forget
   behaviour while still allowing headers.

The client also maintains `state.lastRenderedMessageId` which is updated
every time a message is rendered (initial load) or appended (WebSocket push),
so the read position always reflects the most recently visible message, not just
the initial page load.

---

## REST API Reference

All endpoints are prefixed with `/api` (added by `WebMvcConfig.addPathPrefix`).

Auth: `X-Mock-User-Id: {uuid}` header on all requests.

### Users (demo)

| Method | Path | Description |
|--------|------|-------------|
| GET | /v1/chat/users | List all users (login page picker) |
| POST | /v1/chat/users | Create a demo user by display name |
| POST | /v1/chat/users/batch | Batch fetch user profiles by ID |

### Channels

| Method | Path | Description |
|--------|------|-------------|
| GET | /v1/chat/channels | List joined channels and DMs for caller |
| GET | /v1/chat/channels/browse | All public channels with joined flag |
| POST | /v1/chat/channels | Create a channel |
| POST | /v1/chat/dms?recipientId={uuid} | Open or retrieve a DM with another user |
| POST | /v1/chat/channels/{id}/join | Join a public channel |
| DELETE | /v1/chat/channels/{id}/leave | Leave a channel |
| GET | /v1/chat/channels/{id}/members | List member UUIDs |

### Messages

| Method | Path | Description |
|--------|------|-------------|
| GET | /v1/chat/channels/{id}/messages | Paginated history (`before`, `limit`) |
| POST | /v1/chat/channels/{id}/messages | Send a message |
| PATCH | /v1/chat/channels/{id}/messages/{msgId} | Edit message body |
| DELETE | /v1/chat/channels/{id}/messages/{msgId} | Soft delete |

### Threads

| Method | Path | Description |
|--------|------|-------------|
| GET | /v1/chat/messages/{parentId}/replies | Load replies (`after`, `limit`) |
| POST | /v1/chat/messages/{parentId}/replies | Send a thread reply |
| PATCH | /v1/chat/messages/{parentId}/replies/{replyId} | Edit reply body |
| DELETE | /v1/chat/messages/{parentId}/replies/{replyId} | Soft delete reply |

### Read state

| Method | Path | Description |
|--------|------|-------------|
| POST | /v1/chat/channels/{id}/read?lastReadMessageId={uuid} | Mark channel as read |

---

## WebSocket Event Reference

All events are JSON frames pushed by the server. The client dispatches on
`type`:

```json
{ "type": "MESSAGE_CREATED", "message": { ...MessageDTO }, "channel": null }
{ "type": "MESSAGE_EDITED",  "message": { ...MessageDTO }, "channel": null }
{ "type": "MESSAGE_DELETED", "message": { ...MessageDTO }, "channel": null }
{ "type": "CHANNEL_JOINED",  "message": null, "channel": { ...ChannelDTO } }
{ "type": "CHANNEL_LEFT",    "message": null, "channel": { "id": "..." } }
```

`MESSAGE_*` events carry a `MessageDTO`. `CHANNEL_*` events carry a `ChannelDTO`
(CHANNEL_LEFT carries only the `id` field since the channel is being removed).

### MessageDTO fields

```json
{
  "messageId":  "018f1a2b-...",   // UUID v7; high 48 bits = Unix ms timestamp
  "channelId":  "uuid",
  "senderId":   "uuid",
  "body":       "hello",
  "deleted":    false,
  "edited":     false,
  "hasThread":  false
}
```

`createdAt` is deliberately omitted from the DTO. Clients derive it from the
UUID v7 `messageId` via `new Date(parseInt(messageId.replace(/-/g,'').slice(0,12), 16))`.
This avoids clock skew issues and keeps the wire format lean.

---

## Mock Identity

There is no authentication for the chat domain. Identity is declared by the
caller:

- **REST**: `X-Mock-User-Id: {uuid}` request header
- **WebSocket**: `?userId={uuid}` query parameter on the upgrade URL

Any UUID is accepted without validation. The chat system uses these IDs to
scope channel membership, message ownership, and Redis inbox topics. User
display names and profiles are stored in the `users` MySQL table and fetched
separately by the client via the batch users endpoint.

---

## Client Architecture

### Static files

The frontend is three vanilla JS files served as static assets from
`src/main/resources/static/chat/`:

| File | Purpose |
|------|---------|
| `utils.js` | Shared utilities: `avatarColor(id)`, `initials(name)` |
| `login.js` | User picker and demo user creation |
| `app.js` | Full chat client: channels, messages, WebSocket |

`utils.js` is loaded before the page-specific script in both `login.html` and
`app.html` to avoid duplication.

### State management

All client state lives in a single `state` object:

```js
{
  userId, userName,           // from localStorage after login
  users,                      // id → { id, name } cache
  channels, dms,              // ChannelDTO arrays
  activeChannelId,            // currently open channel
  lastRenderedMessageId,      // tracks read position
  ws                          // WebSocket instance
}
```

### Sender name resolution

Sender names are not embedded in `MessageDTO` to avoid duplicating user data on
every message. Instead, the client batches unknown sender IDs and resolves them
via `POST /api/v1/chat/users/batch`. Results are cached in `state.users` for the
session lifetime.

---

## Scaling Considerations

The current implementation is a single-node demo (one Spring Boot instance, one
Redis, one MySQL, one Cassandra cluster). Below are the known scaling
bottlenecks and their standard solutions.

### 1. Redis pub/sub saturation

**Problem**: A single Redis instance processes all chat events. At high message
volume, the pub/sub throughput saturates and latency grows. Redis pub/sub also
delivers to every subscribed node even if only one user on that node is in the
channel.

**Solution**: Shard Redis instances by `hash(channelId) % N`. Each node
connects to all N Redis instances and publishes/subscribes only to the shard
responsible for that channel. This scales linearly.

**Longer term**: Replace Redis pub/sub with Kafka. Publish each event to a
partitioned Kafka topic (`chat.events` partitioned by `channelId`). Each server
node runs a Kafka consumer group member. Kafka gives durability, replay, and
much higher throughput than Redis pub/sub.

### 2. Cassandra hot partitions

**Problem**: A very active channel accumulates millions of rows in a single
partition (`channel_id`). Large partitions cause GC pressure on Cassandra nodes
and slow compaction.

**Solution**: Re-introduce date bucketing: change the partition key to
`(channel_id, bucket)` where `bucket = 'YYYY-MM-DD'` or `'YYYY-MM-DD-HH'` for
extremely high volume channels. Pagination must then handle cross-bucket queries
transparently. A channel activity heuristic (e.g. `lastMessageId` rate) can
trigger bucket size warnings.

### 3. WebSocket connection count per node

**Problem**: Each WebSocket session holds a file descriptor and memory on the
server. A single JVM node handles thousands of concurrent connections before GC
and thread scheduling become a bottleneck.

**Solution**: Move WebSocket handling to a dedicated non-blocking tier (Netty,
or Spring WebFlux with reactive WebSocket). The chat logic itself is already
stateless per-message — the session maps are the only per-node state. A
reactive runtime handles 10x more concurrent connections per node than a
servlet-based one.

### 4. MySQL becoming a bottleneck on channel list loads

**Problem**: `GET /channels` joins `channel_members`, `channels`, and
`dm_channels` across potentially millions of rows. Under load this becomes a
slow query.

**Solutions**:
- Add a Redis cache keyed by `userId` for the channel list with short TTL
  (invalidated on join/leave/DM create events)
- Denormalise: add `channel_name` and `type` to `channel_members` to avoid the
  join to `channels`
- Shard MySQL by `user_id` range if the user base grows large

### 5. `ALLOW FILTERING` on single-message lookup

**Problem**: `findByChannelIdAndMessageId` uses `ALLOW FILTERING`. Although it
is bounded to a single partition, it is still a full partition scan to find one
row, which becomes expensive for large partitions.

**Solution**: Use the `message_id` as the clustering key directly (as the
current schema does) and change the query to an exact primary key lookup:
`WHERE channel_id = ? AND message_id = ?`. This is already the case in the
current schema — the `ALLOW FILTERING` clause is a belt-and-suspenders holdover
and can be removed once the schema is confirmed to have `message_id` as a
clustering column.

### 6. Read position write amplification

**Problem**: Every channel switch fires a `POST /channels/{id}/read`. With many
users switching channels rapidly, this generates a high volume of MySQL writes
to `channel_members`.

**Solution**: Batch or debounce: write read positions to Redis (low-latency) and
flush to MySQL on a 5–30 second interval or on WebSocket disconnect. This is the
pattern Discord uses ("lazy sync").

### 7. No message search

**Problem**: Cassandra does not support full-text search. There is currently no
way to search message history.

**Solution**: Dual-write messages to Elasticsearch (or OpenSearch) in addition
to Cassandra. Index `(channel_id, sender_id, body, created_at)`. Keep Cassandra
as the primary store for ordered history; use Elasticsearch only for search
queries.

### 8. Single-region deployment

**Problem**: All components (MySQL, Cassandra, Redis, app nodes) are in one
region. Latency is high for users far from that region; any regional failure
takes the whole system down.

**Solution**: Cassandra is designed for multi-region replication natively —
add a second DC with `NetworkTopologyStrategy`. MySQL can use a read replica in
a second region with a regional primary promoted on failover. Redis can be
replaced by a geo-distributed pub/sub layer (e.g. Kafka with cross-region
replication). WebSocket nodes can be deployed in each region with regional Redis
pub/sub; cross-region event delivery goes through Kafka.

---

## Large-Scale Service Decomposition

The current implementation runs everything in one Spring Boot process. At large
scale every tier has different resource profiles, failure modes, and scaling axes.
The right answer is to decompose into independently deployable and scalable
services, each owning exactly the data and compute it needs.

### Target architecture diagram

```
                          ┌─────────────────────────────────────────────┐
                          │              Clients (Browser)               │
                          └──────────────┬──────────────────┬───────────┘
                                         │ HTTP/REST         │ WebSocket
                          ┌──────────────▼──────┐  ┌────────▼──────────┐
                          │   API Gateway /      │  │   WS Gateway /    │
                          │   Load Balancer      │  │   Load Balancer   │
                          │   (stateless)        │  │   (sticky / hash) │
                          └──────┬───────────────┘  └────────┬──────────┘
                                 │                            │
              ┌──────────────────▼──────┐        ┌───────────▼───────────┐
              │   REST API Service      │        │  WebSocket Service     │
              │   (stateless, N nodes)  │        │  (stateful, M nodes)   │
              │                         │        │                        │
              │  Channel Service        │        │  ChatWebSocketHandler  │
              │  Message Service        │        │  sessionsByTopic map   │
              │  User Service           │        │  topicsBySession map   │
              └──┬──────────┬───────────┘        └──────────┬────────────┘
                 │          │                               │
                 │          │         ┌─────────────────────┘
                 │          │         │
                 │    ┌─────▼─────────▼──────────────────────────────┐
                 │    │           Event Bus (Kafka or Redis)          │
                 │    │                                               │
                 │    │  chat.channel.{id}  chat.user.{id}           │
                 │    └───────────────────────────────────────────────┘
                 │
       ┌─────────▼──────────────────────────────────────────────────────┐
       │                        Data Tier                               │
       │                                                                │
       │  ┌──────────────────┐  ┌────────────────────┐  ┌───────────┐  │
       │  │  MySQL Cluster   │  │ Cassandra Cluster  │  │   Redis   │  │
       │  │  (channel roster │  │ (messages, replies)│  │  (cache)  │  │
       │  │   membership,    │  │                    │  │           │  │
       │  │   read pos)      │  │                    │  │           │  │
       │  └──────────────────┘  └────────────────────┘  └───────────┘  │
       └────────────────────────────────────────────────────────────────┘
```

---

### Tier 1 — REST API Service (stateless)

**Responsibility**: All HTTP write operations (send message, create channel,
join/leave, mark read) and read operations (channel list, message history, user
profiles).

**Why stateless**: The REST service holds no per-request state between calls.
Every request carries its own identity (`X-Mock-User-Id` or a real JWT). This
means any node can handle any request — the load balancer can use simple round-
robin. Nodes can be added or removed with zero coordination.

**Scaling axis**: CPU and outbound I/O (Cassandra writes, MySQL reads). Scale
horizontally by adding nodes behind the load balancer. Auto-scale on request
rate or p99 latency.

**Internal decomposition** (sub-services within the REST tier at large scale):

| Sub-service | Owns | Scale driver |
|-------------|------|-------------|
| Channel Service | MySQL channel roster, membership | Low write volume, high read volume |
| Message Service | Cassandra messages, replies | High write volume |
| User/Auth Service | User profiles, tokens | High read volume, cacheable |
| Presence Service | Online/offline status | High update rate (heartbeats) |
| Search Service | Elasticsearch message index | Query volume, index lag |
| Media Service | File uploads, S3 references | Bandwidth, storage |

At moderate scale these can remain packages within one REST process.
Split them into separate deployable units when any one of them has a
meaningfully different scaling curve from the others — e.g. the Media Service
needs 10x the bandwidth of the Channel Service.

**Load balancer**: Standard L7 HTTP load balancer (AWS ALB, NGINX, Envoy).
No special configuration needed — all nodes are identical.

---

### Tier 2 — WebSocket Service (stateful)

**Responsibility**: Maintain persistent WebSocket connections to clients. Receive
events from the event bus and push them to the correct local sessions.

**Why separate from REST**: WebSocket nodes are stateful — each holds a map of
active sessions and their topic subscriptions in memory. Their scaling axis is
connection count, not request throughput. A single node can hold tens of
thousands of open connections but should not also be doing heavy Cassandra reads
for history queries (different resource profile: memory vs I/O).

**Scaling axis**: Number of concurrent WebSocket connections. Scale horizontally
by adding nodes. Each node independently subscribes to the event bus for only
the topics that have local subscribers, so adding nodes does not increase event
bus load proportionally.

**Load balancer / routing**: WebSocket connections are long-lived. Two routing
strategies:

1. **Sticky sessions (simpler)**: Load balancer hashes on client IP or a
   session cookie to route a client to the same node consistently. Simple but
   uneven distribution when client IPs cluster (e.g. corporate NAT).

2. **Consistent hashing on userId (better)**: Hash `userId % numNodes` to pick
   the target node. All WebSocket sessions for a given user land on the same
   node. This also co-locates all sessions of a user (multiple tabs/devices),
   making multi-device delivery trivial — one node handles all of them. Use a
   consistent hash ring so that adding/removing one node only remaps
   `1/N` of users rather than reshuffling all of them.

**Session state**: The session maps (`sessionsByTopic`, `topicsBySession`) are
in-process memory, never persisted. If a node dies, its clients reconnect to
another node via the load balancer. On reconnect the client re-establishes
subscriptions and reloads history via REST. No session state needs to be
replicated or recovered.

**Non-blocking runtime**: At large scale, replace the servlet-based
`TextWebSocketHandler` with a reactive runtime (Spring WebFlux + Reactor Netty
or Vert.x). A single Netty event loop thread handles thousands of concurrent
connections with non-blocking I/O, compared to the one-thread-per-connection
model of a servlet container. This is the single highest-leverage change for
the WebSocket tier.

---

### Tier 3 — Event Bus

**Responsibility**: Decouple REST API nodes from WebSocket nodes. A REST node
publishes an event after writing to the database; WebSocket nodes consume it and
push to their local sessions. Neither tier needs to know about the other.

#### Redis pub/sub (current, up to ~10M messages/day per instance)

```
REST node  →  PUBLISH chat.channel.{id}  →  Redis
                                              ↓
                              all WS nodes subscribed to that topic receive it
```

Simple, low-latency, zero durability. Suitable when:
- Total event throughput fits on one Redis instance
- Missed events on node failure are acceptable (clients reload history on reconnect)

**Sharding Redis**: When a single Redis instance saturates, shard by
`hash(channelId) % N`. Each REST and WS node connects to all N Redis instances
and routes by hash. This scales linearly and requires no changes to the
pub/sub logic beyond a routing layer.

#### Kafka (migration target, unlimited scale)

```
REST node  →  produce to chat-events topic (partition by channelId)
                    ↓
           Kafka cluster (durable, replicated)
                    ↓
           each WS node: own consumer group, consumes ALL partitions
```

Kafka adds durability and replay. The key structural difference from Redis is
the consumer group model — see the Redis vs Kafka section above for details on
the per-node consumer group pattern.

**Kafka topic design for chat**:

| Topic | Partition key | Consumers | Retention |
|-------|--------------|-----------|-----------|
| `chat.messages` | `channelId` | WS nodes (fan-out), Search indexer, Analytics | 7 days |
| `chat.control` | `userId` | WS nodes (fan-out) | 1 day |
| `chat.notifications` | `userId` | Notification Service | 3 days |

Separate topics for messages and control events allow independent retention
policies and consumer group configurations. The notification topic lets a
dedicated Notification Service consume independently without slowing down the
WS fan-out path.

---

### Tier 4 — MySQL Cluster

**Responsibility**: Channel roster, membership, read positions. Low write volume,
high read volume, relational consistency required.

**Scaling strategy**:

```
                     ┌─────────────────────────────────┐
                     │         MySQL Primary            │
                     │  (all writes, strong reads)      │
                     └──────┬──────────┬───────────────┘
                            │          │  sync replication
               ┌────────────▼─┐    ┌───▼────────────┐
               │  Read Replica │    │  Read Replica  │
               │  (region A)   │    │  (region B)    │
               └───────────────┘    └────────────────┘
```

**Read replicas** serve channel list and member list queries — the most frequent
reads. The primary handles all writes (join, leave, mark read, create channel).

**Read-through cache (Redis)**: Channel list (`GET /channels`) is fetched on
every app open and sidebar refresh. Cache it in Redis keyed by `userId` with a
short TTL (5–30 seconds). Invalidate on join, leave, and new DM. This removes
most MySQL read traffic for active users.

```
GET /channels for userId=X
  → check Redis key "channel-list:{userId}"
  → HIT: return cached ChannelListDTO (no MySQL)
  → MISS: query MySQL, write to Redis with TTL, return result
```

**Sharding**: If user count grows to tens of millions, shard `channel_members`
and `channels` by `channelId % N` across MySQL shards. Cross-shard queries
(e.g. "all channels for user X") require a scatter-gather: query all shards and
merge. An alternative is to maintain a separate index table on a dedicated shard:
`user_channels(userId, channelId)` for the reverse lookup.

**ProxySQL / Vitess**: At large scale, put a connection proxy in front of MySQL.
ProxySQL routes reads to replicas and writes to the primary transparently.
Vitess (used by YouTube and Slack) adds horizontal sharding on top of MySQL with
a query routing layer that is transparent to the application.

---

### Tier 5 — Cassandra Cluster

**Responsibility**: All message and reply storage. High write volume, partition-
local reads, no cross-channel queries.

**Scaling strategy**: Cassandra is horizontally scalable by design. Add nodes
and the cluster automatically rebalances partitions via consistent hashing on
the token ring. No application changes needed.

```
                 ┌──────────────────────────────────────┐
                 │          Cassandra Ring               │
                 │                                       │
                 │   Node A    Node B    Node C          │
                 │   (tokens   (tokens   (tokens         │
                 │    0-33%)   34-66%)   67-100%)        │
                 │                                       │
                 │  replication_factor = 3               │
                 │  (each partition on 3 nodes)          │
                 └──────────────────────────────────────┘
```

**Replication factor**: Use RF=3 with `NetworkTopologyStrategy` for multi-DC
deployments. Reads and writes use `LOCAL_QUORUM` consistency so a datacenter
failure does not block operations in other datacenters.

**Hot partition mitigation**: A channel with extremely high message volume
(e.g. a global announcement channel with millions of members) will concentrate
writes on the 3 nodes owning its token range. Mitigation options:

- **Date bucketing**: Change partition key to `(channel_id, bucket_day)`. Writes
  spread across today's bucket and yesterday's bucket as the day rolls over.
  Pagination must handle cross-bucket queries.
- **Write sharding**: Append a shard suffix `(channel_id, shard)` where
  `shard = random(0..N)`. Reads must query all N shards and merge. Useful for
  write-heavy channels where reads can tolerate the scatter cost.

**Compaction strategy**: Use `TimeWindowCompactionStrategy` (TWCS) for
`messages_by_channel`. TWCS groups SSTables by time window and compacts within
each window, producing clean SSTables that are never rewritten once the window
closes. This is optimal for append-only, time-ordered workloads and minimises
write amplification compared to `LeveledCompactionStrategy`.

**DAX / row cache**: Cassandra has a built-in row cache that can cache the most
recently accessed partitions. For small-to-medium channels, enable the row cache
on `messages_by_channel` to serve repeat reads (e.g. users scrolling back
through a low-volume channel) without hitting disk.

---

### Tier 6 — Redis Cache Layer

Separate from the pub/sub Redis (or use different keyspaces/clusters):

| Cache key | Value | TTL | Invalidated by |
|-----------|-------|-----|----------------|
| `channel-list:{userId}` | `ChannelListDTO` JSON | 30s | join, leave, new DM |
| `channel:{channelId}` | `ChannelDTO` JSON | 60s | new message (lastMessageId update) |
| `user:{userId}` | `UserDTO` JSON | 5 min | profile update |
| `members:{channelId}` | List of UUIDs | 60s | join, leave |

At large scale, run the pub/sub Redis and the cache Redis as separate clusters
so that a pub/sub traffic spike does not starve cache reads (and vice versa).

**Cache-aside pattern** (used for all entries above):

```
1. Check cache → HIT: return
2. MISS: query DB
3. Write result to cache with TTL
4. Return result
```

On write operations that invalidate cache, delete the affected keys immediately
(delete-on-write). Do not try to update the cache on write — the DB is the
source of truth; let the cache repopulate lazily on next read.

---

### Tier 7 — CDN and Static Assets

The frontend (`utils.js`, `login.js`, `app.js`, `style.css`, `login.html`,
`app.html`) is currently served by the Spring Boot application. At scale, these
should be served from a CDN:

- Push static assets to S3 (or equivalent object storage)
- Front with CloudFront, Fastly, or Cloudflare
- Version assets by content hash (`app.js?v=a3f2c1`) for cache-busting
- Spring Boot no longer serves static files — it only serves API responses

This removes static asset traffic from the application tier entirely and
delivers assets from an edge node close to the user.

---

### Deployment topology (single region, large scale)

```
Internet
    │
    ▼
┌──────────────────────────────────────────────────────┐
│  CDN (static assets: HTML, JS, CSS)                  │
└──────────────────────────────────────────────────────┘
    │ API calls              │ WebSocket upgrade
    ▼                        ▼
┌─────────────┐      ┌──────────────────┐
│  API L7 LB  │      │  WS L4 LB        │
│  (round-    │      │  (consistent     │
│   robin)    │      │   hash on userId)│
└──────┬──────┘      └────────┬─────────┘
       │                      │
  ┌────▼───────┐         ┌────▼───────────┐
  │ REST API   │         │ WebSocket      │
  │ pods       │         │ pods           │
  │ (auto-     │         │ (scale on      │
  │  scale on  │         │  conn count)   │
  │  CPU/RPS)  │         │                │
  └────┬───────┘         └────────┬───────┘
       │  publish                 │ consume
       └──────────┬───────────────┘
                  ▼
        ┌─────────────────┐
        │  Kafka cluster  │
        │  (or Redis      │
        │   cluster)      │
        └────────┬────────┘
                 │
     ┌───────────┼───────────────┐
     ▼           ▼               ▼
┌─────────┐ ┌──────────┐ ┌─────────────┐
│  MySQL  │ │Cassandra │ │Redis Cache  │
│ Primary │ │ Cluster  │ │ Cluster     │
│   +     │ │ (RF=3)   │ │ (read-      │
│Replicas │ │          │ │  through)   │
└─────────┘ └──────────┘ └─────────────┘
```

---

### Service decomposition migration path

Do not split everything at once. The correct order follows actual bottlenecks:

| Stage | Trigger | Split |
|-------|---------|-------|
| 1 | WebSocket connection count saturates REST nodes | Extract WebSocket service |
| 2 | Redis pub/sub saturates | Shard Redis or migrate to Kafka |
| 3 | MySQL read latency grows | Add read replicas + Redis cache layer |
| 4 | Cassandra hot partitions appear | Add date bucketing, add nodes |
| 5 | REST tier CPU saturates | Scale REST horizontally; split Message vs Channel services if different scale curves |
| 6 | Static asset bandwidth hits app tier | Migrate to CDN |
| 7 | Multi-region latency requirements | Add regional deployments with Cassandra multi-DC |

Splitting prematurely adds operational complexity (distributed tracing, service
discovery, inter-service auth, independent deploy pipelines) before the
performance gains are needed. Each split should be driven by a measured
bottleneck, not by theoretical purity.

---

## Running the Chat App Locally

The playground service runs against real infrastructure: MySQL, Cassandra, and
Redis are deployed into a local [kind](https://kind.sigs.k8s.io) Kubernetes
cluster using Helm charts. The app itself runs outside the cluster as a normal
Spring Boot process.

### Prerequisites

- `kind` — local Kubernetes cluster
- `kubectl` — cluster CLI
- `helm` — chart installer
- Java 21, Maven wrapper (`./mvnw`)

A kind cluster must already exist. Create one if needed:

```bash
kind create cluster
```

### Step 1 — Start the infrastructure

Each script deploys one service into the kind cluster, waits for it to be ready,
and prints the host-accessible port.

```bash
# MySQL  — NodePort 30306
./scripts/deploy-mysql-kind.sh

# Cassandra — NodePort 30942 (also applies the chat schema automatically)
./scripts/deploy-cassandra-kind.sh

# Redis — NodePort 30379
./scripts/deploy-redis-kind.sh
```

All three can be started in any order. Cassandra takes the longest (~2 minutes
for the StatefulSet to be ready).

### Step 2 — Start the playground service

```bash
./scripts/run-service-playground-dev.sh
```

This runs `./mvnw process-resources spring-boot:run` with the `dev` profile.
The `dev` profile connects to:

- MySQL at `127.0.0.1:30306`
- Cassandra at `127.0.0.1:30942`
- Redis at `127.0.0.1:30379`

The service starts on **`http://localhost:2080`**.

### Step 3 — Open the chat app

Navigate to:

```
http://localhost:2080/chat/login
```

1. **Create users** — enter a display name and click "Create & Sign In". Create
   at least two users in different browser tabs (or use a private window for the
   second user) to test real-time messaging.
2. **Create a channel** — click the `+` button next to Channels in the sidebar.
3. **Browse channels** — click the `⊞` button to see all public channels and
   join them.
4. **Open a DM** — click on a user in the DM list or initiate a DM from the
   channel members list.
5. **Send messages** — type in the message box and press Enter. Messages from
   other users arrive in real time via WebSocket without refreshing.

### Swagger UI

The full REST API is documented at:

```
http://localhost:2080/swagger-ui
```

All chat endpoints are listed under the **Chat** tag. Use the `X-Mock-User-Id`
header field in the Authorize dialog to set your user identity for requests.

### Useful debug commands

```bash
# Inspect MySQL
mysql -h 127.0.0.1 -P 30306 -u playground -pplayground-kind playground

# Inspect Cassandra
cqlsh 127.0.0.1 30942
USE chat;
SELECT * FROM messages_by_channel LIMIT 20;

# Inspect Redis pub/sub traffic live
redis-cli -h 127.0.0.1 -p 30379 SUBSCRIBE 'chat.*'

# Flush Redis (clear all cache and pub/sub state)
./scripts/redis-flush-kind.sh
```
