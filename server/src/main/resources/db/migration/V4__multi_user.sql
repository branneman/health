-- V4__multi_user.sql
-- Backfill pattern: add column nullable, backfill existing rows to the 'health' user,
-- then set NOT NULL and add constraints. This is safe on a non-empty database.

-- body_weight
ALTER TABLE body_weight ADD COLUMN user_id UUID;
UPDATE body_weight SET user_id = (SELECT id FROM users WHERE username = (SELECT username FROM users LIMIT 1));
ALTER TABLE body_weight ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE body_weight DROP CONSTRAINT IF EXISTS body_weight_date_key;
ALTER TABLE body_weight ADD CONSTRAINT body_weight_user_date UNIQUE (user_id, date);
ALTER TABLE body_weight ADD FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE body_weight ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- daily_energy: date was PK; becomes compound PK (user_id, date)
ALTER TABLE daily_energy ADD COLUMN user_id UUID;
UPDATE daily_energy SET user_id = (SELECT id FROM users WHERE username = (SELECT username FROM users LIMIT 1));
ALTER TABLE daily_energy ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE daily_energy DROP CONSTRAINT daily_energy_pkey;
ALTER TABLE daily_energy ADD PRIMARY KEY (user_id, date);
ALTER TABLE daily_energy ADD FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

-- workout
ALTER TABLE workout ADD COLUMN user_id UUID;
UPDATE workout SET user_id = (SELECT id FROM users WHERE username = (SELECT username FROM users LIMIT 1));
ALTER TABLE workout ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE workout ADD FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

-- log_entry
ALTER TABLE log_entry ADD COLUMN user_id UUID;
UPDATE log_entry SET user_id = (SELECT id FROM users WHERE username = (SELECT username FROM users LIMIT 1));
ALTER TABLE log_entry ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE log_entry ADD FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE log_entry ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE log_entry ADD COLUMN quick_add_kcal INTEGER;
ALTER TABLE log_entry ADD COLUMN quick_add_label TEXT;

-- meal_template: name was globally unique; now unique per user
ALTER TABLE meal_template ADD COLUMN user_id UUID;
UPDATE meal_template SET user_id = (SELECT id FROM users WHERE username = (SELECT username FROM users LIMIT 1));
ALTER TABLE meal_template ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE meal_template DROP CONSTRAINT IF EXISTS meal_template_name_key;
ALTER TABLE meal_template ADD CONSTRAINT meal_template_user_name UNIQUE (user_id, name);
ALTER TABLE meal_template ADD FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE meal_template ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE meal_template ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- food_item: per-user catalog
ALTER TABLE food_item ADD COLUMN user_id UUID;
UPDATE food_item SET user_id = (SELECT id FROM users WHERE username = (SELECT username FROM users LIMIT 1));
ALTER TABLE food_item ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE food_item DROP CONSTRAINT IF EXISTS food_item_barcode_key;
ALTER TABLE food_item ADD CONSTRAINT food_item_user_barcode UNIQUE (user_id, barcode);
ALTER TABLE food_item ADD FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

-- polar_auth: link Polar platform user to our users table
-- polar_auth.user_id (TEXT) remains as Polar's identifier; we add health_user_id (UUID)
ALTER TABLE polar_auth ADD COLUMN health_user_id UUID REFERENCES users(id) ON DELETE SET NULL;
UPDATE polar_auth SET health_user_id = (SELECT id FROM users WHERE username = (SELECT username FROM users LIMIT 1))
  WHERE health_user_id IS NULL;

-- Indexes
CREATE INDEX ON body_weight   (user_id, date DESC);
CREATE INDEX ON daily_energy  (user_id, date DESC);
CREATE INDEX ON workout       (user_id, date DESC);
CREATE INDEX ON log_entry     (user_id, logged_at DESC);
CREATE INDEX ON meal_template (user_id);
CREATE INDEX ON food_item     (user_id);
