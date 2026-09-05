ALTER TABLE reservations ADD COLUMN people_count INTEGER;

UPDATE reservations SET people_count = CAST(SUBSTRING(notes FROM 'Personas:\s*(\d+)') AS INTEGER)
WHERE notes LIKE '%Personas:%';

UPDATE reservations SET notes = REPLACE(notes, 'Personas: ' || people_count || '. ', '')
WHERE notes LIKE '%Personas:%' AND people_count IS NOT NULL;
