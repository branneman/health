CREATE TABLE body_weight (
    id   UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    date DATE          NOT NULL UNIQUE,
    kg   NUMERIC(5, 2) NOT NULL
);

CREATE TABLE daily_energy (
    date         DATE    PRIMARY KEY,
    bmr_kcal     INTEGER NOT NULL,
    active_kcal  INTEGER NOT NULL,
    total_kcal   INTEGER NOT NULL,
    steps        INTEGER,
    source       TEXT    NOT NULL DEFAULT 'polar'
);

CREATE TABLE workout (
    id            UUID    PRIMARY KEY DEFAULT gen_random_uuid(),
    date          DATE    NOT NULL,
    type          TEXT    NOT NULL,
    duration_secs INTEGER,
    avg_hr        INTEGER,
    kcal          INTEGER
);

CREATE TABLE food_item (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    barcode          TEXT         UNIQUE,
    name             TEXT         NOT NULL,
    kcal_per_100g    NUMERIC(7,2) NOT NULL,
    protein_per_100g NUMERIC(7,2),
    carbs_per_100g   NUMERIC(7,2),
    fat_per_100g     NUMERIC(7,2),
    source           TEXT         NOT NULL DEFAULT 'openfoodfacts'
);

CREATE TABLE meal_template (
    id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL UNIQUE
);

CREATE TABLE meal_template_item (
    template_id  UUID         NOT NULL REFERENCES meal_template(id) ON DELETE CASCADE,
    food_item_id UUID         NOT NULL REFERENCES food_item(id),
    grams        NUMERIC(7,1) NOT NULL,
    PRIMARY KEY (template_id, food_item_id)
);

CREATE TYPE meal_type AS ENUM ('breakfast', 'lunch', 'dinner', 'snack');

CREATE TABLE log_entry (
    id        UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    logged_at TIMESTAMPTZ NOT NULL,
    meal_type meal_type   NOT NULL
);

CREATE TABLE log_entry_item (
    log_entry_id     UUID         NOT NULL REFERENCES log_entry(id) ON DELETE CASCADE,
    food_item_id     UUID         NOT NULL REFERENCES food_item(id),
    grams            NUMERIC(7,1) NOT NULL,
    kcal_per_100g    NUMERIC(7,2) NOT NULL,
    protein_per_100g NUMERIC(7,2),
    carbs_per_100g   NUMERIC(7,2),
    fat_per_100g     NUMERIC(7,2),
    PRIMARY KEY (log_entry_id, food_item_id)
);

CREATE TABLE polar_auth (
    user_id      TEXT        PRIMARY KEY,
    access_token TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ON log_entry (logged_at);
CREATE INDEX ON workout    (date);
