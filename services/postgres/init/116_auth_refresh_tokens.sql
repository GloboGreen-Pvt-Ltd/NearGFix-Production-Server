-- Refresh tokens for shop-user sessions (auth-service RefreshTokenService).
-- Only a SHA-256 hash of each token is stored. Rotated on every /auth/refresh;
-- a token reused after rotation revokes its whole family (stolen-token signal).
CREATE TABLE IF NOT EXISTS auth_refresh_tokens (
    id           UUID PRIMARY KEY,
    token_hash   VARCHAR(64) NOT NULL UNIQUE,
    family_id    UUID NOT NULL,
    user_id      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    shop_id      UUID,
    login_scope  VARCHAR(16) NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL,
    revoked_at   TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_auth_refresh_tokens_family ON auth_refresh_tokens (family_id);
CREATE INDEX IF NOT EXISTS idx_auth_refresh_tokens_user ON auth_refresh_tokens (user_id);
