CREATE TABLE IF NOT EXISTS measurements (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id TEXT NOT NULL,
  measured_at TEXT NOT NULL,
  received_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  source TEXT NOT NULL CHECK (source IN ('scheduled', 'manual')),
  heart_rate REAL,
  spo2 REAL,
  temperature REAL,
  wear_status INTEGER,
  heart_rate_confidence REAL,
  spo2_confidence REAL,
  command_id TEXT,
  raw_json TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_measurements_device_time
  ON measurements(device_id, measured_at DESC);

CREATE UNIQUE INDEX IF NOT EXISTS idx_measurements_command
  ON measurements(command_id) WHERE command_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS commands (
  id TEXT PRIMARY KEY,
  device_id TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  completed_at TEXT,
  status TEXT NOT NULL CHECK (status IN ('pending', 'sent', 'completed', 'timeout')),
  result_measurement_id INTEGER,
  FOREIGN KEY(result_measurement_id) REFERENCES measurements(id)
);

CREATE INDEX IF NOT EXISTS idx_commands_device_created
  ON commands(device_id, created_at DESC);

CREATE TABLE IF NOT EXISTS devices (
  device_id TEXT PRIMARY KEY,
  last_seen_at TEXT NOT NULL,
  app_version TEXT,
  last_ip_hash TEXT
);
