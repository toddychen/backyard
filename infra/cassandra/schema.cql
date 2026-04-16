-- Notification system Cassandra schema
-- Apply once against the cluster before starting any service.
--
-- Requires Cassandra 4.0+ (SAI index on subscriptions.topic_id).
--
-- Java entity classes mirror this schema:
--   services/notification-common/src/main/java/
--     com/backyard/notification/common/entity/PushDestination.java
--     com/backyard/notification/common/entity/Subscription.java
--
-- To apply:
--   cqlsh <host> -f infra/cassandra/schema.cql
--
-- To apply against kind cluster (NodePort 30942 example):
--   cqlsh localhost 30942 -f infra/cassandra/schema.cql

-- ------------------------------------------------------------
-- Keyspace
-- ------------------------------------------------------------
-- SimpleStrategy is appropriate for a single-datacenter cluster
-- (kind, dev, single-DC prod). For multi-DC production, replace
-- with NetworkTopologyStrategy and per-DC replication factors.
--
-- RF=1: we run a single Cassandra node in the kind cluster, so
-- replication_factor=1 is correct. Raise to 3 for a multi-node
-- production cluster; LWT (IF NOT EXISTS) requires QUORUM which
-- needs at least ceil(RF/2)+1 live replicas.

CREATE KEYSPACE IF NOT EXISTS notification
    WITH replication = {
        'class': 'SimpleStrategy',
        'replication_factor': 1  -- single-node kind cluster; raise to 3 for prod
    }
    AND durable_writes = true;

USE notification;

-- ------------------------------------------------------------
-- push_destinations_by_user
-- ------------------------------------------------------------
-- A PushDestination is a unique push token belonging to a user.
-- The push token is the unique identifier — no separate device ID.
-- APNS and FCM both guarantee a token uniquely identifies one
-- app installation.
--
-- Partition key : user_id   — one partition per user
-- Clustering key: push_token — uniqueness enforced by primary key
--
-- State changes (login/logout, notification toggle, token
-- invalidation) are single-row updates within one partition.
--
-- Token rotation: push_token is immutable (Cassandra PK). On
-- rotation, DELETE old row + INSERT new row.
--
-- platform   : 'APNS' or 'FCM'
-- push_token : APNS hex token (≤256 chars) or FCM registration
--              token (≤4096 chars)
-- bundle_id  : APNS only — app bundle ID (e.g. com.example.app)
-- apns_env   : APNS only — 'SANDBOX' or 'PRODUCTION'
-- locale     : BCP-47 tag (e.g. 'en-US') for localised content
-- token_valid: false on APNS 410 or FCM UNREGISTERED response

CREATE TABLE IF NOT EXISTS push_destinations_by_user (
    user_id               uuid,
    push_token            text,
    platform              text,
    bundle_id             text,
    apns_env              text,
    locale                text,
    notifications_enabled boolean,
    token_valid           boolean,
    registered_at         timestamp,
    updated_at            timestamp,
    PRIMARY KEY (user_id, push_token)
) WITH CLUSTERING ORDER BY (push_token ASC)
  AND comment = 'Push destinations. Partition per user, row per push token.';

-- ------------------------------------------------------------
-- subscriptions
-- ------------------------------------------------------------
-- Partition key : user_id  — billions of small (~2KB) partitions,
--                            evenly distributed by Murmur3(user_id)
-- Clustering key: topic_id — sorted; enables range scans per user
--
-- User-facing queries hit the partition key directly (no index).
-- Fan-out queries use the SAI index on topic_id combined with
-- token(user_id) range filtering for parallel worker coverage.
--
-- Rows are hard-deleted on unsubscribe or expiry. No active flag —
-- presence of a row means the subscription is active. Tombstones are
-- cleaned by compaction after gc_grace_seconds (default 10 days).
--
-- topic_id: plain string — no pre-registration required.
--           Examples: 'sports', 'finance', 'breaking-news'

CREATE TABLE IF NOT EXISTS subscriptions (
    user_id       uuid,
    topic_id      text,
    subscribed_at timestamp,
    expires_at    timestamp,  -- null = no expiry; set for transient topics
                              -- (e.g. a sport game topic expires at game end).
                              -- A cleanup task hard-deletes rows where
                              -- expires_at < now(). Cassandra native TTL
                              -- could also auto-delete rows but we prefer
                              -- explicit cleanup for observability.
    PRIMARY KEY (user_id, topic_id)
) WITH CLUSTERING ORDER BY (topic_id ASC)
  AND comment = 'Topic subscriptions. Partition per user, row per topic.';

-- SAI (Storage-Attached Index) on topic_id enables efficient
-- cross-partition fan-out reads without scatter-gather.
-- Combined with token(user_id) range predicates in the query,
-- each fan-out worker reads only its assigned token range slice.
-- Requires Cassandra 4.0+.
CREATE INDEX IF NOT EXISTS subscriptions_topic_idx
    ON subscriptions (topic_id)
    USING 'sai';

-- SAI index on expires_at enables efficient range queries for the
-- subscription cleanup job (WHERE expires_at < :cutoff).
-- Without this, cleanup requires a full table scan (ALLOW FILTERING).
-- Used by CleanupConsumer in notification-consumer, which splits the
-- token space into ranges and pages through expired rows in batches.
CREATE INDEX IF NOT EXISTS subscriptions_expires_at_idx
    ON subscriptions (expires_at)
    USING 'sai';

-- ============================================================
-- Chat keyspace
-- ============================================================
-- Slack-like real-time chat: channels, threads, read state.
-- Design principles:
--   - Tables designed around queries (not entities)
--   - Soft delete only (deleted BOOLEAN) — avoids tombstone accumulation
--   - message_id is UUID v7 (time-ordered): high 48 bits = ms timestamp,
--     sort order equals time order, timestamp is derivable without a
--     separate created_at column
--   - No time-bucketing: plain (channel_id, message_id) partition follows
--     the Discord model — simple cursor pagination regardless of message
--     sparsity; partition growth accepted as a trade-off
--
-- Java entity classes mirror this schema:
--   services/playground/src/main/java/
--     com/backyard/playground/data/persist/cassandra/chat/
--
-- To apply:
--   cqlsh localhost 30942 -f infra/cassandra/schema.cql

CREATE KEYSPACE IF NOT EXISTS chat
    WITH replication = {
        'class': 'SimpleStrategy',
        'replication_factor': 1  -- single-node kind cluster; raise to 3 for prod
    }
    AND durable_writes = true;

USE chat;

-- ------------------------------------------------------------
-- messages_by_channel
-- ------------------------------------------------------------
-- Primary channel feed. One partition per channel_id.
-- message_id is UUID v7 — high 48 bits encode the ms timestamp,
-- so ORDER BY message_id DESC = newest-first without a separate
-- created_at column.
--
-- Pagination cursor: message_id of the oldest message on the page.
--   Initial load : SELECT * WHERE channel_id = ? LIMIT 50
--   Older page   : SELECT * WHERE channel_id = ?
--                    AND message_id < ? LIMIT 50
--
-- has_thread: one-way flag set to true on first reply, never unset.
--   Lets the feed render a thread expand button without querying
--   replies_by_message for every visible message.

CREATE TABLE IF NOT EXISTS messages_by_channel (
    channel_id  uuid,
    message_id  uuid,       -- UUID v7: timestamp in high 48 bits
    sender_id   uuid,
    body        text,
    deleted     boolean,
    edited      boolean,
    has_thread  boolean,
    PRIMARY KEY (channel_id, message_id)
) WITH CLUSTERING ORDER BY (message_id DESC)
  AND comment = 'Channel message feed. Partition per channel, cursor = message_id.';

-- ------------------------------------------------------------
-- replies_by_message
-- ------------------------------------------------------------
-- Thread replies for a single parent message.
-- Partition key: parent_id — all replies co-located.
-- message_id is UUID v7 — ordered oldest-first (ASC) for natural
-- thread reading order. Timestamp derivable from message_id.
--
-- Pagination cursor: message_id of the newest reply on the page.
--   Initial load : SELECT * WHERE parent_id = ? LIMIT 50
--   Newer page   : SELECT * WHERE parent_id = ?
--                    AND message_id > ? LIMIT 50

CREATE TABLE IF NOT EXISTS replies_by_message (
    parent_id   uuid,
    message_id  uuid,       -- UUID v7: timestamp in high 48 bits
    channel_id  uuid,       -- denormalised from parent; avoids cross-table lookup on edit/delete
    sender_id   uuid,
    body        text,
    deleted     boolean,
    PRIMARY KEY ((parent_id), message_id)
) WITH CLUSTERING ORDER BY (message_id ASC)
  AND comment = 'Thread replies. Partition per parent message.';
-- edited boolean added by V001_add_edited_to_replies.cql

-- ------------------------------------------------------------
-- schema_migrations
-- ------------------------------------------------------------
-- Tracks which CQL migration files have been applied.
-- Mirrors the Flyway / Liquibase pattern for relational DBs.
-- The deploy script reads this table to skip already-applied
-- migrations and inserts a row after each successful apply.
--
-- version    : filename of the migration (e.g. V001_add_edited.cql)
-- applied_at : wall-clock time the migration was applied

CREATE TABLE IF NOT EXISTS schema_migrations (
    version     text PRIMARY KEY,
    applied_at  timestamp
) WITH comment = 'Applied CQL migration tracking.';

-- user_last_read is stored in MySQL channel_members.last_read_message_id.
-- Rationale: read state is user×channel relational data with frequent
-- in-place updates — a better fit for MySQL upserts than Cassandra
-- append semantics. Redis would be the production choice for sub-ms
-- latency; MySQL is sufficient for this demo.
