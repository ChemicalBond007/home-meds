ALTER TABLE app_ai_sessions ADD COLUMN provider_calls INTEGER NOT NULL DEFAULT 0;
ALTER TABLE app_ai_sessions ADD COLUMN search_calls INTEGER NOT NULL DEFAULT 0;
ALTER TABLE notification_log ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE push_subscriptions ADD COLUMN failures INTEGER NOT NULL DEFAULT 0;

-- Existing shared entries have no trustworthy provenance or field boundary.
DELETE FROM catalog_cache;
