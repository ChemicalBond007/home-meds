CREATE TRIGGER stock_movement_validate BEFORE INSERT ON stock_movements
WHEN NEW.type IN ('out','discard','move')
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM stock_items s
    WHERE s.id=NEW.stock_item_id AND s.status='active'
      AND s.warehouse_id=NEW.from_warehouse_id
      AND (NEW.type='move' OR (NEW.amount > 0 AND s.units_remaining >= NEW.amount))
  ) THEN RAISE(ABORT,'STOCK_UNAVAILABLE') END;
END;
CREATE TRIGGER stock_movement_apply AFTER INSERT ON stock_movements
WHEN NEW.type IN ('out','discard','move')
BEGIN
  UPDATE stock_items SET
    units_remaining = CASE WHEN NEW.type='move' THEN units_remaining ELSE MAX(0,ROUND(units_remaining-NEW.amount,6)) END,
    status = CASE WHEN NEW.type='discard' THEN 'discarded'
      WHEN NEW.type='out' AND ROUND(units_remaining-NEW.amount,6)<=0 THEN 'empty' ELSE status END,
    warehouse_id = CASE WHEN NEW.type='move' THEN NEW.to_warehouse_id ELSE warehouse_id END,
    opened = CASE WHEN NEW.type='out' THEN 1 ELSE opened END
  WHERE id=NEW.stock_item_id;
END;
