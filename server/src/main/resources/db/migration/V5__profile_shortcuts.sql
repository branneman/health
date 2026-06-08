-- V5__profile_shortcuts.sql

CREATE TABLE user_profile (
  user_id        UUID         PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  height_cm      INTEGER      NOT NULL,
  birth_year     INTEGER      NOT NULL,
  sex            TEXT         NOT NULL CHECK (sex IN ('male', 'female')),
  goal_weight_kg NUMERIC(5,2) NOT NULL,
  activity_level TEXT         NOT NULL
    CHECK (activity_level IN ('sedentary', 'lightly_active', 'moderately_active')),
  target_deficit INTEGER      NOT NULL DEFAULT 300,
  phase          TEXT         NOT NULL DEFAULT 'loss' CHECK (phase IN ('loss', 'maintenance')),
  vacation_mode  BOOLEAN      NOT NULL DEFAULT false,
  updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE shortcut (
  id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id    UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  emoji      TEXT        NOT NULL,
  label      TEXT        NOT NULL,
  kcal       INTEGER     NOT NULL,
  sort_order INTEGER     NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (user_id, sort_order)
);

CREATE INDEX ON shortcut (user_id, sort_order);
