CREATE TABLE audit_logs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f','now')),
  actor_id TEXT,
  actor_name TEXT,
  group_id TEXT,
  group_name TEXT,
  action TEXT NOT NULL,
  method TEXT NOT NULL,
  route TEXT NOT NULL,
  outcome TEXT NOT NULL CHECK(outcome IN ('success','failure')),
  http_status INTEGER NOT NULL,
  target_id TEXT,
  subject_name TEXT
);
-- Keep identity snapshots even if accounts or groups change later.
CREATE INDEX audit_time ON audit_logs(created_at,id);
CREATE INDEX audit_actor_time ON audit_logs(actor_id,created_at,id);
CREATE INDEX audit_group_time ON audit_logs(group_id,created_at,id);
CREATE INDEX lookup_time ON ai_lookup_logs(created_at,id);
CREATE INDEX dose_time ON dose_logs(created_at,id);
CREATE INDEX movement_time ON stock_movements(created_at,id);
CREATE INDEX usage_date ON ai_usage(local_date);
