ALTER TABLE business_config ADD COLUMN admin_phone VARCHAR(50);

UPDATE business_config SET admin_phone = '573123197433' WHERE admin_phone IS NULL;
