-- =====================================================================
-- Multiple models per advert & chat file attachments
-- =====================================================================

-- Join table for adverts having multiple 3D models
CREATE TABLE advert_models
(
    advert_id  UUID    NOT NULL REFERENCES adverts (id) ON DELETE CASCADE,
    model_id   UUID    NOT NULL REFERENCES models (id) ON DELETE CASCADE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (advert_id, model_id)
);
CREATE INDEX ix_advert_models_advert ON advert_models (advert_id);
CREATE INDEX ix_advert_models_model ON advert_models (model_id);

-- Migrate existing single model references into advert_models
INSERT INTO advert_models (advert_id, model_id, sort_order)
SELECT id, model_id, 0
FROM adverts
WHERE model_id IS NOT NULL
ON CONFLICT DO NOTHING;

-- Message attachments for 3D model files in private messaging
ALTER TABLE messages ADD COLUMN file_name VARCHAR(255);
ALTER TABLE messages ADD COLUMN file_size BIGINT;
ALTER TABLE messages ADD COLUMN object_key TEXT;
ALTER TABLE messages ADD COLUMN content_type VARCHAR(120);
