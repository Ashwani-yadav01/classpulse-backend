-- ClassPulse V6: Activity ordering and queueing
ALTER TABLE activities ADD COLUMN IF NOT EXISTS order_index INT NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS idx_activities_session_order ON activities(session_id, order_index ASC);
