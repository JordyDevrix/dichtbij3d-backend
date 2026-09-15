-- ------------------------------------------------------------------ --
-- Email MFA support
-- ------------------------------------------------------------------ --
ALTER TABLE users ADD COLUMN email_mfa_enabled BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE email_mfa_tokens
(
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    code_hash  VARCHAR(64) NOT NULL,
    purpose    VARCHAR(20) NOT NULL DEFAULT 'LOGIN',
    expires_at TIMESTAMPTZ NOT NULL,
    used       BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_email_mfa_tokens_user ON email_mfa_tokens (user_id);
CREATE INDEX ix_email_mfa_tokens_expires ON email_mfa_tokens (expires_at);
