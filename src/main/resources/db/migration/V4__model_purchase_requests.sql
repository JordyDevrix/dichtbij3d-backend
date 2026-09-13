-- =====================================================================
-- Paid models are no longer handed out for free.
--
-- There is no payment provider yet, so a paid model works like a sale
-- advert: the buyer asks, the two settle it in a private thread, and the
-- owner is the only one who can hand over access. One open request per
-- buyer per model.
-- =====================================================================

CREATE TABLE model_purchase_requests
(
    id              UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    model_id        UUID        NOT NULL REFERENCES models (id) ON DELETE CASCADE,
    buyer_id        UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    conversation_id UUID REFERENCES conversations (id) ON DELETE SET NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    message         TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at      TIMESTAMPTZ,
    UNIQUE (model_id, buyer_id)
);
CREATE INDEX ix_model_purchase_requests_model ON model_purchase_requests (model_id, status);
CREATE INDEX ix_model_purchase_requests_buyer ON model_purchase_requests (buyer_id);

-- Entitlements that were granted for free on a paid model are the result of the
-- old "acquire" behaviour. They stay valid, but they are marked as a share so the
-- numbers do not claim a purchase that never happened.
UPDATE model_entitlements e
SET source = 'SHARE'
WHERE source = 'PURCHASE'
  AND EXISTS (SELECT 1 FROM models m WHERE m.id = e.model_id AND m.price_cents > 0);
