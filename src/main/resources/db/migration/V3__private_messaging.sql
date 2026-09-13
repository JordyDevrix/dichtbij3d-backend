-- =====================================================================
-- Private messaging
--
-- One conversation per pair of users per advert (a NULL advert_id is the
-- general "direct message" thread, exactly like Marktplaats keeps a thread
-- per listing). Participants are stored ordered (a < b) so that looking up
-- an existing conversation is a single unique-index probe instead of an
-- OR over two columns.
-- =====================================================================

CREATE TABLE conversations
(
    id              UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    participant_a   UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    participant_b   UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    advert_id       UUID REFERENCES adverts (id) ON DELETE SET NULL,
    last_message_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_message    TEXT,
    a_read_at       TIMESTAMPTZ,
    b_read_at       TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_conversations_participants CHECK (participant_a < participant_b)
);

CREATE UNIQUE INDEX ux_conversations_pair
    ON conversations (participant_a, participant_b, advert_id) NULLS NOT DISTINCT;
CREATE INDEX ix_conversations_a ON conversations (participant_a, last_message_at DESC);
CREATE INDEX ix_conversations_b ON conversations (participant_b, last_message_at DESC);

CREATE TABLE messages
(
    id              UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    conversation_id UUID        NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    sender_id       UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    kind            VARCHAR(20) NOT NULL DEFAULT 'TEXT',
    body            TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ
);

CREATE INDEX ix_messages_conversation ON messages (conversation_id, created_at DESC);
