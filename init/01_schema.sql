CREATE TABLE IF NOT EXISTS body_weight (
    id   UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    date DATE          NOT NULL UNIQUE,
    kg   NUMERIC(5, 2) NOT NULL
);
