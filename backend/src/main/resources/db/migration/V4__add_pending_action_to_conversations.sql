ALTER TABLE conversations
    ADD COLUMN IF NOT EXISTS pending_action VARCHAR(50),
    ADD COLUMN IF NOT EXISTS pending_action_data TEXT;
