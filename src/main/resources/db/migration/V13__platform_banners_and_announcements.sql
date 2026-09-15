CREATE TABLE platform_banner (
    id UUID PRIMARY KEY,
    enabled BOOLEAN NOT NULL DEFAULT false,
    title VARCHAR(200) NOT NULL DEFAULT '',
    subtitle TEXT,
    badge_text VARCHAR(100),
    button_text VARCHAR(100),
    link_url TEXT,
    image_key TEXT,
    image_url TEXT,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

INSERT INTO platform_banner (id, enabled, title, subtitle, badge_text, button_text, link_url, image_key, image_url, updated_at)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    false,
    'Welkom bij Dichtbij3D',
    'Vind 3D-printers en ontwerpers bij jou in de buurt of verkoop je eigen creaties.',
    'Nieuw',
    'Ontdek marktplaats',
    '/marketplace',
    NULL,
    NULL,
    now()
);

CREATE TABLE platform_announcements (
    id UUID PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    type VARCHAR(30) NOT NULL DEFAULT 'INFO',
    event_date TIMESTAMP WITH TIME ZONE,
    link_url TEXT,
    link_text VARCHAR(100),
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_platform_announcements_active ON platform_announcements(active, created_at DESC);
