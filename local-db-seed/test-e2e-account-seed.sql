-- E2E test account seed — test+e2e@bran.name
-- Fixed UUID: 00000000-0000-0000-0000-000000000010
--
-- Run before each E2E test run to reset to a known state:
--   psql $DATABASE_URL -v "e2e_password_hash=<hash>" < local-db-seed/test-e2e-account-seed.sql
--
-- Generate a bcrypt hash of the E2E account password:
--   python3 -c "import bcrypt; print(bcrypt.hashpw(b'PASSWORD', bcrypt.gensalt()).decode())"
--
-- In CI this is driven by the E2E_DATABASE_URL and E2E_PASSWORD_HASH secrets.

BEGIN;

-- Delete existing account and all cascaded data (body_weight, log_entry, meal_template, etc.)
DELETE FROM users WHERE id       = '00000000-0000-0000-0000-000000000010';
DELETE FROM users WHERE username = 'test+e2e@bran.name';

INSERT INTO users (id, username, password_hash) VALUES
    ('00000000-0000-0000-0000-000000000010', 'test+e2e@bran.name', :'e2e_password_hash');

INSERT INTO user_profile (user_id, height_cm, birth_year, sex, goal_weight_kg, activity_level, target_deficit) VALUES
    ('00000000-0000-0000-0000-000000000010', 182, 1985, 'male', 78.0, 'lightly_active', 400);

-- Meal template for one-tap button test.
-- sort_order IS NOT NULL makes it appear as a pinned button on the log screen.
INSERT INTO meal_template (id, user_id, name, quick_add_kcal, sort_order) VALUES
    ('00000000-0000-0000-0000-000000000011', '00000000-0000-0000-0000-000000000010', 'Breakfast', 550, 1);

-- Seven days of calorie data so the dashboard renders something real.
INSERT INTO daily_energy (user_id, date, bmr_kcal, active_kcal, total_kcal, steps, source)
SELECT
    '00000000-0000-0000-0000-000000000010',
    CURRENT_DATE - n,
    1900,
    280 + (n * 15),
    2180 + (n * 15),
    7200 + (n * 80),
    'polar'
FROM generate_series(1, 7) AS n;

-- Seven days of body weight — no entry for today so the dashboard weight chip starts empty.
INSERT INTO body_weight (user_id, date, kg)
SELECT
    '00000000-0000-0000-0000-000000000010',
    CURRENT_DATE - n,
    83.0 - (n * 0.1)
FROM generate_series(1, 7) AS n;

COMMIT;
