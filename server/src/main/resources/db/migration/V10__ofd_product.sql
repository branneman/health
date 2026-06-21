CREATE SCHEMA catalog;

CREATE TABLE catalog.product (
    id               UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    barcode          TEXT         NOT NULL UNIQUE,
    name             TEXT         NOT NULL,
    kcal_per_100g    DECIMAL(7,2) NOT NULL,
    protein_per_100g DECIMAL(7,2),
    carbs_per_100g   DECIMAL(7,2),
    fat_per_100g     DECIMAL(7,2),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX catalog_product_fts_idx
    ON catalog.product
    USING GIN (to_tsvector('simple', name));

CREATE TABLE catalog.import_state (
    id                  BOOLEAN     PRIMARY KEY DEFAULT TRUE CHECK (id = TRUE),
    last_delta_end_ts   BIGINT,
    last_full_import_at TIMESTAMPTZ
);

INSERT INTO catalog.import_state (id) VALUES (TRUE);
