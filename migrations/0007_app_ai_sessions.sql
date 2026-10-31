-- One AI round run on the Android app. The phone calls the model provider
-- directly; the Worker only issues encrypted keys, runs database tools and logs.
CREATE TABLE app_ai_sessions (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  group_id TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  kind TEXT NOT NULL CHECK(kind IN ('lookup','chat')),
  target_user_id TEXT,
  query TEXT,
  known_medicines TEXT NOT NULL DEFAULT '[]',
  tool_calls INTEGER NOT NULL DEFAULT 0,
  expires_at INTEGER NOT NULL,
  finished_at INTEGER,
  success INTEGER
);
CREATE INDEX app_ai_sessions_expiry ON app_ai_sessions(expires_at);
