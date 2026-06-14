ALTER TABLE meal_template
  ADD COLUMN quick_add_kcal INTEGER,
  ADD COLUMN sort_order INTEGER;

CREATE UNIQUE INDEX meal_template_user_sort_order
  ON meal_template (user_id, sort_order)
  WHERE sort_order IS NOT NULL;
