-- V7__polar_sync.sql

-- Short-lived OAuth CSRF state tokens (one-time use, GC'd by cron)
CREATE TABLE polar_connect_state (
    state      TEXT        PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL
);

-- Polar's hashed exercise ID for idempotent upserts
ALTER TABLE workout ADD COLUMN polar_exercise_id TEXT;
ALTER TABLE workout ADD CONSTRAINT workout_user_polar_id
    UNIQUE (user_id, polar_exercise_id);

-- Unique health user per polar_auth row (allows upsert-by-user)
ALTER TABLE polar_auth
    ADD CONSTRAINT polar_auth_health_user_id_unique
    UNIQUE (health_user_id);
