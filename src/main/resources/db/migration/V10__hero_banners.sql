-- =====================================================================
-- Hero Banners & Settings
-- =====================================================================

CREATE TABLE hero_banners
(
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title            VARCHAR(200),
    subtitle         TEXT,
    media_url        TEXT         NOT NULL,
    media_type       VARCHAR(20)  NOT NULL DEFAULT 'IMAGE',
    duration_seconds INTEGER,
    link_url         VARCHAR(500),
    link_text        VARCHAR(100),
    sort_order       INTEGER      NOT NULL DEFAULT 0,
    enabled          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_hero_banners_sort ON hero_banners (sort_order ASC, created_at ASC);

CREATE TABLE hero_banner_settings
(
    id                       INTEGER PRIMARY KEY DEFAULT 1,
    slide_duration_seconds   INTEGER NOT NULL DEFAULT 5,
    show_for_logged_in_users BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_single_banner_settings CHECK (id = 1)
);

INSERT INTO hero_banner_settings (id, slide_duration_seconds, show_for_logged_in_users)
VALUES (1, 5, FALSE)
ON CONFLICT (id) DO NOTHING;
