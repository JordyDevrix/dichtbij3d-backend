-- =====================================================================
-- Conversation participant invitation & approval status
-- =====================================================================

-- 1. Add status and invited_by to conversation_participants
ALTER TABLE conversation_participants
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'JOINED';

ALTER TABLE conversation_participants
    ADD COLUMN invited_by UUID REFERENCES users (id) ON DELETE SET NULL;

-- 2. Index for user participant status queries
CREATE INDEX ix_conversation_participants_user_status
    ON conversation_participants (user_id, status);
