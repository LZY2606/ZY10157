PRAGMA journal_mode=WAL;

CREATE TABLE IF NOT EXISTS app_meta (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS import_batch (
  id TEXT PRIMARY KEY,
  imported_at INTEGER NOT NULL,
  source_hash TEXT NOT NULL,
  segment_count INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS raw_segment (
  id TEXT PRIMARY KEY,
  batch_id TEXT NOT NULL REFERENCES import_batch(id),
  device_id TEXT NOT NULL,
  start_nanos INTEGER NOT NULL,
  end_nanos INTEGER NOT NULL,
  center_frequency_hz INTEGER NOT NULL,
  resolution_bandwidth_hz INTEGER NOT NULL,
  bucket_count INTEGER NOT NULL,
  power_buckets_gz BLOB NOT NULL,
  device_temperature_c REAL NOT NULL,
  calibration_version TEXT NOT NULL,
  content_hash TEXT NOT NULL,
  UNIQUE(device_id, content_hash)
);

CREATE TABLE IF NOT EXISTS correction_model (
  version INTEGER PRIMARY KEY AUTOINCREMENT,
  created_at INTEGER NOT NULL,
  active INTEGER NOT NULL,
  note TEXT NOT NULL,
  body_json TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS auto_link_version (
  version INTEGER PRIMARY KEY AUTOINCREMENT,
  created_at INTEGER NOT NULL,
  model_version INTEGER NOT NULL REFERENCES correction_model(version),
  note TEXT NOT NULL,
  body_json TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS manual_decision (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  version INTEGER NOT NULL UNIQUE,
  created_at INTEGER NOT NULL,
  expected_version INTEGER NOT NULL,
  action TEXT NOT NULL,
  target TEXT NOT NULL,
  device_id TEXT,
  offset_hz REAL,
  note TEXT NOT NULL,
  evidence_json TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS decision_conflict (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  created_at INTEGER NOT NULL,
  expected_version INTEGER NOT NULL,
  actual_version INTEGER NOT NULL,
  attempted_json TEXT NOT NULL,
  existing_json TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS publish_batch (
  id TEXT PRIMARY KEY,
  model_version INTEGER NOT NULL,
  auto_version INTEGER NOT NULL,
  decision_version INTEGER NOT NULL,
  published_at INTEGER NOT NULL,
  event_count INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS published_event (
  id TEXT NOT NULL,
  publish_id TEXT NOT NULL REFERENCES publish_batch(id),
  alternate_id TEXT NOT NULL,
  selected INTEGER NOT NULL,
  fingerprint TEXT NOT NULL,
  event_json TEXT NOT NULL,
  PRIMARY KEY(publish_id, alternate_id, id)
);

CREATE INDEX IF NOT EXISTS idx_raw_device_time ON raw_segment(device_id, start_nanos, end_nanos);
CREATE INDEX IF NOT EXISTS idx_raw_calibration ON raw_segment(calibration_version);
