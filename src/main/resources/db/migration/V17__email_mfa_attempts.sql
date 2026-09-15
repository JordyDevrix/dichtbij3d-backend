-- ------------------------------------------------------------------ --
-- Add attempts column to email_mfa_tokens to cap verification tries
-- ------------------------------------------------------------------ --
ALTER TABLE email_mfa_tokens ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0;
