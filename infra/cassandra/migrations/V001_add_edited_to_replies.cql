-- Migration V001: add edited flag to replies_by_message
--
-- Mirrors the edited column already present on messages_by_channel.
-- Set to true by RepliesByMessageRepositoryImpl.updateBody.
--
-- Apply against existing clusters:
--   cqlsh localhost 30942 -f infra/cassandra/migrations/V001_add_edited_to_replies.cql
--
-- Fresh clusters: schema.cql already includes this column — skip.
-- Note: CQL ALTER TABLE ADD has no IF NOT EXISTS; running this on a
-- fresh cluster will produce "column already exists" — safe to ignore.

USE chat;

ALTER TABLE replies_by_message ADD edited boolean;
