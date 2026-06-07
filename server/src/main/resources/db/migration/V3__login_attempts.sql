CREATE TABLE login_attempts (
    key          TEXT        PRIMARY KEY,
    attempts     INT         NOT NULL,
    locked_until TIMESTAMPTZ
);
