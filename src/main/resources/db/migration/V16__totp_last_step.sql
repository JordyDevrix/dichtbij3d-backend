-- ------------------------------------------------------------------ --
-- Store last verified TOTP time step to prevent replay attacks (RFC 6238)
-- ------------------------------------------------------------------ --
ALTER TABLE users ADD COLUMN last_totp_step BIGINT;
