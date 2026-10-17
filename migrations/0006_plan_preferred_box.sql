-- A preference is separate from the box actually used on the last dose.
ALTER TABLE medication_plans ADD COLUMN preferred_stock_item_id TEXT REFERENCES stock_items(id) ON DELETE SET NULL;
