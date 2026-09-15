CREATE TABLE platform_settings (
    id UUID PRIMARY KEY,
    maintenance_enabled BOOLEAN NOT NULL DEFAULT false,
    maintenance_title VARCHAR(200) NOT NULL DEFAULT 'Tijdelijk offline voor onderhoud',
    maintenance_message TEXT NOT NULL DEFAULT 'Dichtbij3D is momenteel niet bereikbaar wegens gepland onderhoud. We zijn zo snel mogelijk weer terug!',
    maintenance_until TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

INSERT INTO platform_settings (id, maintenance_enabled, maintenance_title, maintenance_message, maintenance_until, updated_at)
VALUES (
    '00000000-0000-0000-0000-000000000002',
    false,
    'Tijdelijk offline voor onderhoud',
    'Dichtbij3D is momenteel niet bereikbaar wegens gepland onderhoud. We zijn zo snel mogelijk weer terug!',
    NULL,
    now()
);
