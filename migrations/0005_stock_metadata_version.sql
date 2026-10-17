ALTER TABLE stock_items ADD COLUMN metadata_version INTEGER NOT NULL DEFAULT 0;

-- Dose/out/move operations must also invalidate an administrator's stale form.
CREATE TRIGGER stock_metadata_changed AFTER UPDATE OF expiry_date,batch_number,serial_number,trace_code,
  units_per_package,opened,units_remaining,status,warehouse_id ON stock_items
BEGIN
  UPDATE stock_items SET metadata_version=OLD.metadata_version+1 WHERE id=NEW.id;
END;

CREATE TRIGGER stock_trace_metadata_map AFTER UPDATE OF trace_code ON stock_items
WHEN NEW.trace_code IS NOT NULL AND OLD.trace_code IS NOT NEW.trace_code
BEGIN
  INSERT INTO product_codes(group_id,code,product_id) VALUES(NEW.group_id,substr(NEW.trace_code,1,7),NEW.product_id)
    ON CONFLICT(group_id,code) DO UPDATE SET product_id=excluded.product_id;
END;
