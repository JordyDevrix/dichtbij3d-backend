-- Default tags and printer catalogue for the cost calculator.

INSERT INTO tags (slug, label_nl, label_en, label_de, label_fr, is_default)
VALUES ('printer', 'Printer', 'Printer', 'Drucker', 'Imprimeur', TRUE),
       ('modeller', 'Modelleur', 'Modeller', 'Modellierer', 'Modeleur', TRUE),
       ('spoed', 'Spoed', 'Urgent', 'Eilig', 'Urgent', TRUE),
       ('prototype', 'Prototype', 'Prototype', 'Prototyp', 'Prototype', TRUE),
       ('reserveonderdeel', 'Reserveonderdeel', 'Spare part', 'Ersatzteil', 'Piece detachee', TRUE),
       ('miniatuur', 'Miniatuur', 'Miniature', 'Miniatur', 'Miniature', TRUE),
       ('cosplay', 'Cosplay', 'Cosplay', 'Cosplay', 'Cosplay', TRUE),
       ('resin', 'Resin', 'Resin', 'Resin', 'Resine', TRUE),
       ('fdm', 'FDM', 'FDM', 'FDM', 'FDM', TRUE),
       ('groot-formaat', 'Groot formaat', 'Large format', 'Grossformat', 'Grand format', TRUE),
       ('kleur', 'Kleur (multicolor)', 'Multicolor', 'Mehrfarbig', 'Multicolore', TRUE),
       ('functioneel', 'Functioneel', 'Functional', 'Funktional', 'Fonctionnel', TRUE)
ON CONFLICT (slug) DO NOTHING;

INSERT INTO printer_models (brand, name, watts, standby_watts, purchase_price_cents, expected_lifetime_hours, technology)
VALUES ('Bambu Lab', 'X1 Carbon', 350, 12, 119900, 8000, 'FDM'),
       ('Bambu Lab', 'P1S', 320, 10, 69900, 8000, 'FDM'),
       ('Bambu Lab', 'P1P', 300, 10, 59900, 8000, 'FDM'),
       ('Bambu Lab', 'A1', 250, 8, 39900, 6000, 'FDM'),
       ('Bambu Lab', 'A1 mini', 150, 6, 22900, 6000, 'FDM'),
       ('Bambu Lab', 'H2D', 420, 14, 199900, 8000, 'FDM'),
       ('Prusa', 'MK4S', 240, 8, 109900, 10000, 'FDM'),
       ('Prusa', 'MK3S+', 220, 7, 89900, 10000, 'FDM'),
       ('Prusa', 'XL (single tool)', 380, 12, 219900, 10000, 'FDM'),
       ('Prusa', 'MINI+', 160, 6, 44900, 8000, 'FDM'),
       ('Creality', 'Ender 3 V3 SE', 200, 6, 19900, 4000, 'FDM'),
       ('Creality', 'Ender 3 S1 Pro', 270, 7, 29900, 4000, 'FDM'),
       ('Creality', 'K1', 350, 10, 49900, 5000, 'FDM'),
       ('Creality', 'K1 Max', 400, 12, 79900, 5000, 'FDM'),
       ('Anycubic', 'Kobra 2 Pro', 250, 7, 24900, 4000, 'FDM'),
       ('Anycubic', 'Photon Mono M5s', 120, 5, 34900, 3000, 'RESIN'),
       ('Elegoo', 'Neptune 4 Pro', 260, 7, 26900, 4000, 'FDM'),
       ('Elegoo', 'Mars 4 Ultra', 100, 5, 27900, 3000, 'RESIN'),
       ('Ultimaker', 'S5', 500, 15, 599900, 12000, 'FDM'),
       ('Ultimaker', 'S3', 350, 12, 399900, 12000, 'FDM'),
       ('Formlabs', 'Form 4', 220, 10, 359900, 6000, 'RESIN'),
       ('Voron', 'V2.4 (350mm)', 600, 10, 150000, 10000, 'FDM'),
       ('Sovol', 'SV06 Plus', 280, 8, 29900, 4000, 'FDM'),
       ('Artillery', 'Sidewinder X4 Pro', 300, 8, 32900, 4000, 'FDM')
ON CONFLICT (brand, name) DO NOTHING;
