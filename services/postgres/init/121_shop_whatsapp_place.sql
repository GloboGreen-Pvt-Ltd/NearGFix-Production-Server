-- Partner Create Account: owner's WhatsApp number + exact Google Maps place.
-- latitude / longitude / pincode already exist on shops.
ALTER TABLE shops ADD COLUMN IF NOT EXISTS whatsapp_number VARCHAR(20);
ALTER TABLE shops ADD COLUMN IF NOT EXISTS google_place_id VARCHAR(255);
