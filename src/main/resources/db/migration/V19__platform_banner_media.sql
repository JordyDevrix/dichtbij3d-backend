CREATE TABLE platform_banner_media (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    banner_id UUID NOT NULL REFERENCES platform_banner(id) ON DELETE CASCADE,
    media_type VARCHAR(20) NOT NULL DEFAULT 'IMAGE',
    media_url TEXT NOT NULL,
    media_key TEXT,
    duration_seconds INT NOT NULL DEFAULT 5,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_banner_media_banner_sort ON platform_banner_media(banner_id, sort_order ASC);

-- Migrate existing single banner image if present
INSERT INTO platform_banner_media (id, banner_id, media_type, media_url, media_key, duration_seconds, sort_order, created_at)
SELECT
    gen_random_uuid(),
    id,
    'IMAGE',
    COALESCE(image_url, '/api/files/' || image_key),
    image_key,
    5,
    0,
    now()
FROM platform_banner
WHERE (image_url IS NOT NULL AND TRIM(image_url) != '') OR (image_key IS NOT NULL AND TRIM(image_key) != '');
