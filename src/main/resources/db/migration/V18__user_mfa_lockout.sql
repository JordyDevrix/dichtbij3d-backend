-- ------------------------------------------------------------------ --
-- Add failed_mfa_attempts and mfa_locked_until to users table
-- ------------------------------------------------------------------ --
ALTER TABLE users ADD COLUMN failed_mfa_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN mfa_locked_until TIMESTAMP WITH TIME ZONE;
