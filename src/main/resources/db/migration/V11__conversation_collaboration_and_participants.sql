-- =====================================================================
-- Conversation collaboration: multi-participant threads and user search
-- =====================================================================

-- 1. Create table for conversation participants
CREATE TABLE conversation_participants
(
    id              UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    conversation_id UUID        NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    user_id         UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    read_at         TIMESTAMPTZ,
    joined_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_conversation_participant UNIQUE (conversation_id, user_id)
);

CREATE INDEX ix_conversation_participants_user ON conversation_participants (user_id);
CREATE INDEX ix_conversation_participants_conversation ON conversation_participants (conversation_id);

-- 2. Add optional title for collaboration/project threads
ALTER TABLE conversations ADD COLUMN title VARCHAR(140);

-- 3. Relax participant_a and participant_b constraints to support group collaboration threads
ALTER TABLE conversations ALTER COLUMN participant_a DROP NOT NULL;
ALTER TABLE conversations ALTER COLUMN participant_b DROP NOT NULL;
ALTER TABLE conversations DROP CONSTRAINT IF EXISTS ck_conversations_participants;

-- 4. Replace strict pair index with partial index for 1-to-1 conversations
DROP INDEX IF EXISTS ux_conversations_pair;
CREATE UNIQUE INDEX ux_conversations_pair
    ON conversations (participant_a, participant_b, advert_id)
    WHERE participant_a IS NOT NULL AND participant_b IS NOT NULL;

-- 5. Backfill conversation_participants from existing conversations
INSERT INTO conversation_participants (conversation_id, user_id, read_at, joined_at)
SELECT id, participant_a, a_read_at, created_at
FROM conversations
WHERE participant_a IS NOT NULL
ON CONFLICT (conversation_id, user_id) DO NOTHING;

INSERT INTO conversation_participants (conversation_id, user_id, read_at, joined_at)
SELECT id, participant_b, b_read_at, created_at
FROM conversations
WHERE participant_b IS NOT NULL
ON CONFLICT (conversation_id, user_id) DO NOTHING;
