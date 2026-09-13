-- =====================================================================
-- Categories, and blocking people.
--
-- Categories are a fixed platform taxonomy (no user-made ones) and apply
-- to adverts *and* models, so searching for "homelab" finds the prints
-- and the models in that category.
-- =====================================================================

ALTER TABLE adverts ADD COLUMN category VARCHAR(40) NOT NULL DEFAULT 'OTHER';
ALTER TABLE models ADD COLUMN category VARCHAR(40) NOT NULL DEFAULT 'OTHER';
CREATE INDEX ix_adverts_category ON adverts (category);
CREATE INDEX ix_models_category ON models (category);

-- Best-effort categorisation of what is already there, based on the words
-- people used. Anything unmatched simply stays in "Overig".
UPDATE adverts SET category = 'HOMELAB_IT'
WHERE lower(title || ' ' || description) ~ '(homelab|server|rack|raspberry|ssd|patch|switch|kabelgoot)';
UPDATE adverts SET category = 'ELECTRONICS_CASES'
WHERE category = 'OTHER' AND lower(title || ' ' || description) ~ '(behuizing|sensor|arduino|esp32|pcb|elektronica)';
UPDATE adverts SET category = 'TABLETOP_MINIATURES'
WHERE category = 'OTHER' AND lower(title || ' ' || description) ~ '(miniatuur|miniature|tabletop|dnd|warhammer|dice|dobbel)';
UPDATE adverts SET category = 'HOME_LIVING'
WHERE category = 'OTHER' AND lower(title || ' ' || description) ~ '(vaas|lamp|plank|organizer|opberg|interieur|haak)';
UPDATE adverts SET category = 'SPARE_PARTS_REPAIR'
WHERE category = 'OTHER' AND lower(title || ' ' || description) ~ '(onderdeel|reserve|vervang|reparat|clip|beugel)';

-- =====================================================================
-- Blocking
--
-- A block is one-directional bookkeeping but enforced both ways: once
-- either side blocks, neither can start a chat or reach the other.
-- =====================================================================

CREATE TABLE user_blocks
(
    id         UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    blocker_id UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    blocked_id UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    reason     TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (blocker_id, blocked_id),
    CHECK (blocker_id <> blocked_id)
);
CREATE INDEX ix_user_blocks_blocker ON user_blocks (blocker_id);
CREATE INDEX ix_user_blocks_blocked ON user_blocks (blocked_id);
