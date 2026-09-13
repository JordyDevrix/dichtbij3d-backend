-- =====================================================================
-- Dichtbij3D baseline schema
--
-- NOTE ON pgvector: deliberately NOT installed. The search requirements
-- (tags, free text, post date, sorting) are lexical, not semantic, and
-- are fully covered by PostgreSQL full text search (tsvector/GIN) plus
-- pg_trgm for fuzzy matching. Adding pgvector would be dead weight.
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ------------------------------------------------------------------ --
-- Users & authentication
-- ------------------------------------------------------------------ --
CREATE TABLE users
(
    id               UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    email            VARCHAR(255) NOT NULL,
    password_hash    TEXT         NOT NULL,
    display_name     VARCHAR(60)  NOT NULL,
    bio              TEXT,
    avatar_key       TEXT,
    gender           VARCHAR(20)  NOT NULL DEFAULT 'RATHER_NOT_SAY',
    contact_email    VARCHAR(255),
    contact_phone    VARCHAR(40),
    website          VARCHAR(255),
    city             VARCHAR(120),
    country          VARCHAR(2)   NOT NULL DEFAULT 'NL',
    locale           VARCHAR(5)   NOT NULL DEFAULT 'nl',
    enabled          BOOLEAN      NOT NULL DEFAULT TRUE,
    disabled_reason  TEXT,
    email_verified   BOOLEAN      NOT NULL DEFAULT FALSE,
    totp_secret      TEXT,
    totp_enabled     BOOLEAN      NOT NULL DEFAULT FALSE,
    last_login_at    TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at       TIMESTAMPTZ
);
CREATE UNIQUE INDEX ux_users_email ON users (lower(email));
CREATE INDEX ix_users_created_at ON users (created_at DESC);

CREATE TABLE user_roles
(
    user_id UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role    VARCHAR(30) NOT NULL,
    PRIMARY KEY (user_id, role)
);

CREATE TABLE refresh_tokens
(
    id          UUID PRIMARY KEY    DEFAULT gen_random_uuid(),
    user_id     UUID       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash  VARCHAR(64) NOT NULL UNIQUE,
    family_id   UUID       NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    replaced_by VARCHAR(64),
    user_agent  VARCHAR(255),
    ip_address  VARCHAR(64),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_refresh_tokens_user ON refresh_tokens (user_id);
CREATE INDEX ix_refresh_tokens_family ON refresh_tokens (family_id);

CREATE TABLE passkey_credentials
(
    id             UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    user_id        UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    credential_id  VARCHAR(512) NOT NULL UNIQUE,
    attested_data  TEXT         NOT NULL,
    sign_count     BIGINT       NOT NULL DEFAULT 0,
    label          VARCHAR(100) NOT NULL DEFAULT 'Passkey',
    transports     VARCHAR(255),
    backed_up      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_used_at   TIMESTAMPTZ
);
CREATE INDEX ix_passkey_user ON passkey_credentials (user_id);

CREATE TABLE webauthn_challenges
(
    id          UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    challenge   VARCHAR(255) NOT NULL UNIQUE,
    user_id     UUID REFERENCES users (id) ON DELETE CASCADE,
    purpose     VARCHAR(20)  NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ------------------------------------------------------------------ --
-- Tags
-- ------------------------------------------------------------------ --
CREATE TABLE tags
(
    id         UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    slug       VARCHAR(60)  NOT NULL UNIQUE,
    label_nl   VARCHAR(80)  NOT NULL,
    label_en   VARCHAR(80)  NOT NULL,
    label_de   VARCHAR(80)  NOT NULL,
    label_fr   VARCHAR(80)  NOT NULL,
    is_default BOOLEAN      NOT NULL DEFAULT FALSE,
    usage_count INTEGER     NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_tags_slug_trgm ON tags USING GIN (slug gin_trgm_ops);

-- ------------------------------------------------------------------ --
-- 3D models (uploaded files that can be shared or sold)
-- ------------------------------------------------------------------ --
CREATE TABLE models
(
    id            UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    owner_id      UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    title         VARCHAR(140) NOT NULL,
    description   TEXT,
    license       VARCHAR(40)  NOT NULL DEFAULT 'CC_BY_NC',
    price_cents   INTEGER      NOT NULL DEFAULT 0,
    currency      VARCHAR(3)   NOT NULL DEFAULT 'EUR',
    visibility    VARCHAR(20)  NOT NULL DEFAULT 'PUBLIC',
    thumbnail_key TEXT,
    download_count INTEGER     NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at    TIMESTAMPTZ
);
CREATE INDEX ix_models_owner ON models (owner_id);

CREATE TABLE model_files
(
    id           UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    model_id     UUID         NOT NULL REFERENCES models (id) ON DELETE CASCADE,
    object_key   TEXT         NOT NULL,
    file_name    VARCHAR(255) NOT NULL,
    content_type VARCHAR(120) NOT NULL DEFAULT 'application/octet-stream',
    size_bytes   BIGINT       NOT NULL DEFAULT 0,
    sort_order   INTEGER      NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_model_files_model ON model_files (model_id);

CREATE TABLE model_entitlements
(
    id         UUID PRIMARY KEY    DEFAULT gen_random_uuid(),
    model_id   UUID        NOT NULL REFERENCES models (id) ON DELETE CASCADE,
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    source     VARCHAR(20) NOT NULL DEFAULT 'PURCHASE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (model_id, user_id)
);

-- ------------------------------------------------------------------ --
-- Adverts (the unified marketplace entity)
-- ------------------------------------------------------------------ --
CREATE TABLE adverts
(
    id                UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    author_id         UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type              VARCHAR(30)  NOT NULL,
    title             VARCHAR(140) NOT NULL,
    description       TEXT         NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    price_cents       INTEGER,
    currency          VARCHAR(3)   NOT NULL DEFAULT 'EUR',
    allow_bidding     BOOLEAN      NOT NULL DEFAULT FALSE,
    budget_min_cents  INTEGER,
    budget_max_cents  INTEGER,
    hidden_after_accept BOOLEAN    NOT NULL DEFAULT FALSE,
    accepted_by       UUID REFERENCES users (id) ON DELETE SET NULL,
    accepted_at       TIMESTAMPTZ,
    model_id          UUID REFERENCES models (id) ON DELETE SET NULL,
    city              VARCHAR(120),
    postal_code       VARCHAR(12),
    deadline          DATE,
    view_count        INTEGER      NOT NULL DEFAULT 0,
    reaction_count    INTEGER      NOT NULL DEFAULT 0,
    bid_count         INTEGER      NOT NULL DEFAULT 0,
    search_vector     TSVECTOR,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at        TIMESTAMPTZ,
    deleted_reason    TEXT,
    deleted_by        UUID REFERENCES users (id) ON DELETE SET NULL
);
CREATE INDEX ix_adverts_created_at ON adverts (created_at DESC);
CREATE INDEX ix_adverts_view_count ON adverts (view_count DESC);
CREATE INDEX ix_adverts_type_status ON adverts (type, status);
CREATE INDEX ix_adverts_author ON adverts (author_id);
CREATE INDEX ix_adverts_search ON adverts USING GIN (search_vector);
CREATE INDEX ix_adverts_title_trgm ON adverts USING GIN (title gin_trgm_ops);

CREATE OR REPLACE FUNCTION adverts_search_vector_update() RETURNS TRIGGER AS
$$
BEGIN
    NEW.search_vector :=
            setweight(to_tsvector('simple', coalesce(NEW.title, '')), 'A') ||
            setweight(to_tsvector('simple', coalesce(NEW.city, '')), 'B') ||
            setweight(to_tsvector('simple', coalesce(NEW.description, '')), 'C');
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_adverts_search_vector
    BEFORE INSERT OR UPDATE OF title, description, city
    ON adverts
    FOR EACH ROW
EXECUTE FUNCTION adverts_search_vector_update();

CREATE TABLE advert_tags
(
    advert_id UUID NOT NULL REFERENCES adverts (id) ON DELETE CASCADE,
    tag_id    UUID NOT NULL REFERENCES tags (id) ON DELETE CASCADE,
    PRIMARY KEY (advert_id, tag_id)
);
CREATE INDEX ix_advert_tags_tag ON advert_tags (tag_id);

CREATE TABLE advert_images
(
    id         UUID PRIMARY KEY    DEFAULT gen_random_uuid(),
    advert_id  UUID        NOT NULL REFERENCES adverts (id) ON DELETE CASCADE,
    object_key TEXT        NOT NULL,
    sort_order INTEGER     NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_advert_images_advert ON advert_images (advert_id);

CREATE TABLE advert_reactions
(
    id             UUID PRIMARY KEY    DEFAULT gen_random_uuid(),
    advert_id      UUID        NOT NULL REFERENCES adverts (id) ON DELETE CASCADE,
    author_id      UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    body           TEXT        NOT NULL,
    is_application BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at     TIMESTAMPTZ,
    deleted_reason TEXT
);
CREATE INDEX ix_advert_reactions_advert ON advert_reactions (advert_id, created_at);

CREATE TABLE bids
(
    id           UUID PRIMARY KEY    DEFAULT gen_random_uuid(),
    advert_id    UUID        NOT NULL REFERENCES adverts (id) ON DELETE CASCADE,
    bidder_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    amount_cents INTEGER     NOT NULL,
    message      TEXT,
    status       VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_bids_advert ON bids (advert_id, amount_cents DESC);

-- Smart view counting: one row per (advert, viewer, dedup bucket).
CREATE TABLE advert_views
(
    id          UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    advert_id   UUID         NOT NULL REFERENCES adverts (id) ON DELETE CASCADE,
    viewer_key  VARCHAR(64)  NOT NULL,
    bucket      BIGINT       NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (advert_id, viewer_key, bucket)
);
CREATE INDEX ix_advert_views_advert ON advert_views (advert_id);

CREATE TABLE reports
(
    id          UUID PRIMARY KEY    DEFAULT gen_random_uuid(),
    advert_id   UUID REFERENCES adverts (id) ON DELETE CASCADE,
    user_id     UUID REFERENCES users (id) ON DELETE CASCADE,
    reporter_id UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    reason      TEXT        NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ
);

-- ------------------------------------------------------------------ --
-- Notifications
-- ------------------------------------------------------------------ --
CREATE TABLE notifications
(
    id         UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    user_id    UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type       VARCHAR(40)  NOT NULL,
    title      VARCHAR(180) NOT NULL,
    body       TEXT,
    link       VARCHAR(255),
    read_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_notifications_user ON notifications (user_id, created_at DESC);

-- ------------------------------------------------------------------ --
-- Printer catalogue for the cost calculator
-- ------------------------------------------------------------------ --
CREATE TABLE printer_models
(
    id             UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    brand          VARCHAR(60)  NOT NULL,
    name           VARCHAR(80)  NOT NULL,
    watts          INTEGER      NOT NULL,
    standby_watts  INTEGER      NOT NULL DEFAULT 6,
    purchase_price_cents INTEGER NOT NULL DEFAULT 0,
    expected_lifetime_hours INTEGER NOT NULL DEFAULT 5000,
    technology     VARCHAR(20)  NOT NULL DEFAULT 'FDM',
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (brand, name)
);

CREATE TABLE audit_log
(
    id         UUID PRIMARY KEY    DEFAULT gen_random_uuid(),
    actor_id   UUID REFERENCES users (id) ON DELETE SET NULL,
    action     VARCHAR(60) NOT NULL,
    target_type VARCHAR(40),
    target_id  UUID,
    detail     TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_log_created ON audit_log (created_at DESC);
