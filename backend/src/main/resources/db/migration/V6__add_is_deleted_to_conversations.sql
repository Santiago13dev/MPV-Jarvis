-- V6: Add is_deleted column for soft-delete on conversations
ALTER TABLE conversations ADD COLUMN is_deleted BOOLEAN DEFAULT FALSE;
CREATE INDEX idx_conversations_not_deleted ON conversations(is_deleted) WHERE is_deleted = FALSE;
