-- V5: Add remote_jid column to conversations for @lid JID support
ALTER TABLE conversations ADD COLUMN remote_jid VARCHAR(100);
