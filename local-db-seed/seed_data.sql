-- Local development seed data. Never run in production.
--
-- Load after starting the server (Flyway must have created the schema first):
--   psql $DATABASE_URL < local-db-seed/seed_data.sql
--
-- Reset DB and reload:
--   docker compose down -v && docker compose up -d postgres postgres-mcp
--   ./gradlew :server:run   (applies Flyway migrations)
--   psql $DATABASE_URL < local-db-seed/seed_data.sql

-- ~90 days of body weight with a realistic slow downward trend (~5 kg over 3 months)
-- Seeds data for the first user in the database (local dev only).
INSERT INTO body_weight (user_id, date, kg)
SELECT u.id, v.date, v.kg
FROM (SELECT id FROM users LIMIT 1) u,
(VALUES
    ('2026-03-01'::date, 87.20::numeric),
    ('2026-03-02'::date, 87.05::numeric),
    ('2026-03-04'::date, 86.90::numeric),
    ('2026-03-05'::date, 87.10::numeric),
    ('2026-03-06'::date, 86.75::numeric),
    ('2026-03-08'::date, 86.60::numeric),
    ('2026-03-09'::date, 86.80::numeric),
    ('2026-03-11'::date, 86.45::numeric),
    ('2026-03-13'::date, 86.30::numeric),
    ('2026-03-15'::date, 86.10::numeric),
    ('2026-03-16'::date, 86.35::numeric),
    ('2026-03-18'::date, 85.95::numeric),
    ('2026-03-20'::date, 85.80::numeric),
    ('2026-03-22'::date, 85.60::numeric),
    ('2026-03-23'::date, 85.85::numeric),
    ('2026-03-25'::date, 85.50::numeric),
    ('2026-03-27'::date, 85.40::numeric),
    ('2026-03-29'::date, 85.20::numeric),
    ('2026-03-31'::date, 85.00::numeric),
    ('2026-04-02'::date, 84.90::numeric),
    ('2026-04-03'::date, 85.10::numeric),
    ('2026-04-05'::date, 84.75::numeric),
    ('2026-04-07'::date, 84.60::numeric),
    ('2026-04-09'::date, 84.80::numeric),
    ('2026-04-10'::date, 84.50::numeric),
    ('2026-04-12'::date, 84.35::numeric),
    ('2026-04-14'::date, 84.20::numeric),
    ('2026-04-16'::date, 84.45::numeric),
    ('2026-04-18'::date, 84.10::numeric),
    ('2026-04-20'::date, 83.95::numeric),
    ('2026-04-22'::date, 83.80::numeric),
    ('2026-04-24'::date, 84.00::numeric),
    ('2026-04-26'::date, 83.70::numeric),
    ('2026-04-28'::date, 83.55::numeric),
    ('2026-04-30'::date, 83.40::numeric),
    ('2026-05-02'::date, 83.25::numeric),
    ('2026-05-04'::date, 83.50::numeric),
    ('2026-05-06'::date, 83.15::numeric),
    ('2026-05-08'::date, 83.00::numeric),
    ('2026-05-10'::date, 83.20::numeric),
    ('2026-05-12'::date, 82.90::numeric),
    ('2026-05-14'::date, 82.75::numeric),
    ('2026-05-16'::date, 83.00::numeric),
    ('2026-05-18'::date, 82.65::numeric),
    ('2026-05-20'::date, 82.50::numeric),
    ('2026-05-22'::date, 82.70::numeric),
    ('2026-05-24'::date, 82.40::numeric),
    ('2026-05-26'::date, 82.25::numeric),
    ('2026-05-28'::date, 82.45::numeric),
    ('2026-05-30'::date, 82.20::numeric),
    ('2026-06-01'::date, 82.05::numeric),
    ('2026-06-02'::date, 81.90::numeric)
) AS v(date, kg);
