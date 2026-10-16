PRAGMA foreign_keys = ON;

CREATE TABLE groups (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  join_code TEXT NOT NULL UNIQUE,
  timezone TEXT NOT NULL DEFAULT 'Asia/Shanghai',
  meal_times TEXT NOT NULL DEFAULT '{"morning":"08:00","noon":"12:00","evening":"18:00","bedtime":"21:30"}',
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE TABLE users (
  id TEXT PRIMARY KEY,
  username TEXT NOT NULL UNIQUE COLLATE NOCASE,
  role TEXT NOT NULL DEFAULT 'user' CHECK(role IN ('user','admin')),
  group_id TEXT REFERENCES groups(id),
  is_elderly INTEGER NOT NULL DEFAULT 0 CHECK(is_elderly IN (0,1)),
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE TABLE one_time_secrets (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id),
  kind TEXT NOT NULL CHECK(kind IN ('temporary','recovery')),
  secret_hash TEXT NOT NULL UNIQUE,
  expires_at INTEGER NOT NULL,
  used_at INTEGER
);
CREATE TABLE sessions (
  token_hash TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id),
  binding_only INTEGER NOT NULL DEFAULT 0,
  expires_at INTEGER NOT NULL
);
CREATE INDEX sessions_user ON sessions(user_id);
CREATE TABLE webauthn_credentials (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id),
  public_key TEXT NOT NULL,
  counter INTEGER NOT NULL,
  transports TEXT NOT NULL DEFAULT '[]'
);
CREATE TABLE auth_challenges (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id),
  kind TEXT NOT NULL CHECK(kind IN ('register','authenticate')),
  challenge TEXT NOT NULL,
  session_hash TEXT,
  expires_at INTEGER NOT NULL
);
CREATE TABLE totp_secrets (
  user_id TEXT PRIMARY KEY REFERENCES users(id),
  encrypted_secret TEXT NOT NULL,
  verified INTEGER NOT NULL DEFAULT 0,
  last_step INTEGER NOT NULL DEFAULT -1
);
CREATE TABLE rate_limits (
  key TEXT PRIMARY KEY,
  count INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE TABLE products (
  id TEXT PRIMARY KEY,
  group_id TEXT NOT NULL REFERENCES groups(id),
  name TEXT NOT NULL,
  generic_name TEXT,
  manufacturer TEXT,
  specification TEXT,
  indications TEXT,
  precautions TEXT,
  base_unit TEXT NOT NULL DEFAULT '片',
  units_per_package REAL NOT NULL DEFAULT 1 CHECK(units_per_package > 0),
  status TEXT NOT NULL DEFAULT 'confirmed' CHECK(status IN ('pending','confirmed')),
  sources TEXT NOT NULL DEFAULT '[]',
  created_by TEXT NOT NULL REFERENCES users(id),
  updated_by TEXT REFERENCES users(id),
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX products_group ON products(group_id);
CREATE TABLE product_codes (
  group_id TEXT NOT NULL REFERENCES groups(id),
  code TEXT NOT NULL,
  product_id TEXT NOT NULL REFERENCES products(id),
  PRIMARY KEY(group_id,code)
);
CREATE TABLE catalog_cache (
  code TEXT PRIMARY KEY,
  data TEXT NOT NULL,
  updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE TABLE warehouses (
  id TEXT PRIMARY KEY,
  group_id TEXT NOT NULL REFERENCES groups(id),
  name TEXT NOT NULL
);
CREATE TABLE stock_items (
  id TEXT PRIMARY KEY,
  group_id TEXT NOT NULL REFERENCES groups(id),
  product_id TEXT NOT NULL REFERENCES products(id),
  warehouse_id TEXT NOT NULL REFERENCES warehouses(id),
  trace_code TEXT UNIQUE,
  batch_number TEXT,
  serial_number TEXT,
  expiry_date TEXT,
  opened INTEGER NOT NULL DEFAULT 0,
  units_per_package REAL NOT NULL CHECK(units_per_package > 0),
  units_remaining REAL NOT NULL CHECK(units_remaining >= 0),
  status TEXT NOT NULL DEFAULT 'active' CHECK(status IN ('active','empty','discarded')),
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX stock_group_product ON stock_items(group_id,product_id,status);
CREATE TABLE stock_movements (
  id TEXT PRIMARY KEY,
  stock_item_id TEXT NOT NULL REFERENCES stock_items(id),
  type TEXT NOT NULL CHECK(type IN ('in','out','move','discard','dose','undo_dose')),
  amount REAL NOT NULL,
  from_warehouse_id TEXT REFERENCES warehouses(id),
  to_warehouse_id TEXT REFERENCES warehouses(id),
  actor_id TEXT NOT NULL REFERENCES users(id),
  dose_log_id TEXT,
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE TABLE medication_plans (
  id TEXT PRIMARY KEY,
  group_id TEXT NOT NULL REFERENCES groups(id),
  user_id TEXT NOT NULL REFERENCES users(id),
  product_id TEXT REFERENCES products(id),
  medicine_name TEXT NOT NULL,
  amount REAL NOT NULL CHECK(amount > 0),
  unit TEXT NOT NULL,
  times TEXT NOT NULL,
  frequency TEXT NOT NULL CHECK(frequency IN ('daily','weekly','interval','as_needed')),
  weekdays TEXT NOT NULL DEFAULT '[]',
  interval_days INTEGER NOT NULL DEFAULT 1 CHECK(interval_days >= 1),
  start_date TEXT NOT NULL,
  end_date TEXT,
  meal_relation TEXT NOT NULL DEFAULT 'none' CHECK(meal_relation IN ('none','before','after','with')),
  notes TEXT NOT NULL DEFAULT '',
  enabled INTEGER NOT NULL DEFAULT 1,
  notify_family INTEGER NOT NULL DEFAULT 0,
  last_stock_item_id TEXT REFERENCES stock_items(id),
  created_by TEXT NOT NULL REFERENCES users(id),
  updated_by TEXT NOT NULL REFERENCES users(id),
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX plans_group ON medication_plans(group_id,enabled);
CREATE TABLE dose_logs (
  id TEXT PRIMARY KEY,
  plan_id TEXT NOT NULL REFERENCES medication_plans(id),
  user_id TEXT NOT NULL REFERENCES users(id),
  group_id TEXT NOT NULL REFERENCES groups(id),
  local_date TEXT NOT NULL,
  scheduled_time TEXT NOT NULL,
  status TEXT NOT NULL CHECK(status IN ('taken','skipped','undone')),
  taken_at TEXT,
  amount REAL NOT NULL CHECK(amount >= 0),
  unit TEXT NOT NULL,
  stock_item_id TEXT REFERENCES stock_items(id),
  actor_id TEXT NOT NULL REFERENCES users(id),
  undone_by TEXT REFERENCES users(id),
  undone_at TEXT,
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE UNIQUE INDEX dose_once ON dose_logs(plan_id,local_date,scheduled_time) WHERE status <> 'undone';
CREATE INDEX dose_group_date ON dose_logs(group_id,local_date);
CREATE TABLE push_subscriptions (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id),
  endpoint TEXT NOT NULL UNIQUE,
  p256dh TEXT NOT NULL,
  auth TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE TABLE notification_log (
  plan_id TEXT NOT NULL REFERENCES medication_plans(id),
  local_date TEXT NOT NULL,
  scheduled_time TEXT NOT NULL,
  kind TEXT NOT NULL CHECK(kind IN ('self','family')),
  subscription_id TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'sending',
  claimed_at INTEGER NOT NULL,
  PRIMARY KEY(plan_id,local_date,scheduled_time,kind,subscription_id)
);
CREATE TABLE app_settings (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
CREATE TABLE ai_usage (
  scope TEXT NOT NULL,
  local_date TEXT NOT NULL,
  kind TEXT NOT NULL CHECK(kind IN ('lookup','chat')),
  count INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY(scope,local_date,kind)
);
CREATE TABLE ai_lookup_logs (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id),
  group_id TEXT REFERENCES groups(id),
  query TEXT NOT NULL,
  success INTEGER NOT NULL,
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TRIGGER dose_stock_validate BEFORE INSERT ON dose_logs
WHEN NEW.status = 'taken' AND NEW.stock_item_id IS NOT NULL
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM stock_items s JOIN medication_plans p ON p.id = NEW.plan_id
    JOIN products pr ON pr.id = s.product_id
    WHERE s.id = NEW.stock_item_id AND s.group_id = NEW.group_id
      AND s.product_id = p.product_id AND s.status = 'active'
      AND s.units_remaining >= NEW.amount AND pr.base_unit = NEW.unit
  ) THEN RAISE(ABORT, 'STOCK_UNAVAILABLE') END;
END;

CREATE TRIGGER dose_stock_apply AFTER INSERT ON dose_logs
WHEN NEW.status = 'taken' AND NEW.stock_item_id IS NOT NULL
BEGIN
  UPDATE stock_items SET
    units_remaining = MAX(0, ROUND(units_remaining - NEW.amount, 6)),
    status = CASE WHEN ROUND(units_remaining - NEW.amount, 6) <= 0 THEN 'empty' ELSE 'active' END,
    opened = 1
  WHERE id = NEW.stock_item_id;
  INSERT INTO stock_movements(id,stock_item_id,type,amount,actor_id,dose_log_id)
    VALUES(NEW.id || ':dose',NEW.stock_item_id,'dose',NEW.amount,NEW.actor_id,NEW.id);
  UPDATE medication_plans SET last_stock_item_id = NEW.stock_item_id WHERE id = NEW.plan_id;
END;

CREATE TRIGGER dose_stock_undo AFTER UPDATE OF status ON dose_logs
WHEN OLD.status = 'taken' AND NEW.status = 'undone' AND OLD.stock_item_id IS NOT NULL
BEGIN
  UPDATE stock_items SET units_remaining = ROUND(units_remaining + OLD.amount, 6),
    status = CASE WHEN status = 'empty' THEN 'active' ELSE status END
    WHERE id = OLD.stock_item_id;
  INSERT INTO stock_movements(id,stock_item_id,type,amount,actor_id,dose_log_id)
    VALUES(NEW.id || ':undo',OLD.stock_item_id,'undo_dose',OLD.amount,NEW.undone_by,NEW.id);
END;
