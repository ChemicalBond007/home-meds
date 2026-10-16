CREATE TRIGGER product_code_immutable BEFORE UPDATE OF product_id ON product_codes
WHEN OLD.product_id <> NEW.product_id
BEGIN
  SELECT RAISE(ABORT,'CODE_CONFLICT');
END;
