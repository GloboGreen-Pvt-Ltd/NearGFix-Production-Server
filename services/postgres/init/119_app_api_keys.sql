-- Third-party API keys the apps fetch at runtime (never shipped in an app build).
-- Read through GET /auth/api-keys/{name} by any signed-in user; only names on
-- the server allow-list are served. Set the value here, e.g.:
--   UPDATE app_api_keys SET key_value = '<your key>', updated_at = now() WHERE key_name = 'GOOGLE_MAPS';
-- Restrict the Google key in Google Cloud (Static Maps API only + app / referrer limits).

CREATE TABLE IF NOT EXISTS app_api_keys (
    key_name    VARCHAR(60) PRIMARY KEY,
    key_value   TEXT,
    is_active   BOOLEAN NOT NULL DEFAULT TRUE,
    description TEXT,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO app_api_keys (key_name, key_value, description)
VALUES ('GOOGLE_MAPS', NULL, 'Google Maps Static API — Partner Pickup Tracking map')
ON CONFLICT (key_name) DO NOTHING;
