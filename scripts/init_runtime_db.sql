CREATE DATABASE IF NOT EXISTS wiki_agent_runtime DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE wiki_agent_runtime;

CREATE TABLE IF NOT EXISTS med_metadata_table (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  hospital_id VARCHAR(64) NOT NULL,
  db_name VARCHAR(128) NOT NULL,
  table_name VARCHAR(128) NOT NULL,
  table_comment TEXT,
  table_type VARCHAR(32),
  sync_batch_id VARCHAR(64) NOT NULL,
  sync_time DATETIME NOT NULL,
  UNIQUE KEY uk_table (hospital_id, db_name, table_name)
);

CREATE TABLE IF NOT EXISTS med_metadata_column (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  hospital_id VARCHAR(64) NOT NULL,
  db_name VARCHAR(128) NOT NULL,
  table_name VARCHAR(128) NOT NULL,
  column_name VARCHAR(128) NOT NULL,
  data_type VARCHAR(64),
  column_type VARCHAR(128),
  is_nullable VARCHAR(8),
  column_key VARCHAR(32),
  column_default TEXT,
  column_comment TEXT,
  sync_batch_id VARCHAR(64) NOT NULL,
  sync_time DATETIME NOT NULL,
  UNIQUE KEY uk_column (hospital_id, db_name, table_name, column_name)
);

CREATE TABLE IF NOT EXISTS med_metadata_sync_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  hospital_id VARCHAR(64) NOT NULL,
  db_name VARCHAR(128) NOT NULL,
  table_name VARCHAR(128),
  field_name VARCHAR(128),
  change_type VARCHAR(32),
  change_desc TEXT,
  sync_batch_id VARCHAR(64) NOT NULL,
  sync_time DATETIME NOT NULL
);

CREATE TABLE IF NOT EXISTS med_field_mapping (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  hospital_id VARCHAR(64) NOT NULL,
  rule_id VARCHAR(64) NOT NULL,
  business_field VARCHAR(128) NOT NULL,
  db_name VARCHAR(128) NOT NULL,
  table_name VARCHAR(128) NOT NULL,
  column_name VARCHAR(128) NOT NULL,
  data_type VARCHAR(64),
  status VARCHAR(32) NOT NULL DEFAULT 'confirmed',
  updated_by VARCHAR(64),
  updated_at DATETIME NOT NULL,
  UNIQUE KEY uk_mapping (hospital_id, rule_id, business_field)
);

CREATE TABLE IF NOT EXISTS med_generated_sql (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  sql_id VARCHAR(64) NOT NULL UNIQUE,
  hospital_id VARCHAR(64) NOT NULL,
  rule_id VARCHAR(64) NOT NULL,
  dialect VARCHAR(32) NOT NULL,
  sql_text TEXT NOT NULL,
  sql_status VARCHAR(32) NOT NULL,
  validation_message TEXT,
  generated_by VARCHAR(64),
  generated_at DATETIME NOT NULL
);

CREATE TABLE IF NOT EXISTS med_sql_run_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  run_id VARCHAR(64) NOT NULL UNIQUE,
  sql_id VARCHAR(64),
  hospital_id VARCHAR(64) NOT NULL,
  rule_id VARCHAR(64) NOT NULL,
  stat_start_time DATETIME,
  stat_end_time DATETIME,
  run_status VARCHAR(32) NOT NULL,
  result_value DECIMAL(18, 4),
  error_message TEXT,
  duration_ms INT,
  run_by VARCHAR(64),
  run_time DATETIME NOT NULL
);

CREATE TABLE IF NOT EXISTS med_index_diagnose_report (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  report_id VARCHAR(64) NOT NULL UNIQUE,
  hospital_id VARCHAR(64) NOT NULL,
  rule_id VARCHAR(64) NOT NULL,
  diagnose_type VARCHAR(32) NOT NULL,
  problem_detail TEXT,
  repair_suggest TEXT,
  repair_sql TEXT,
  diagnose_time DATETIME NOT NULL,
  status TINYINT NOT NULL DEFAULT 0,
  trigger_type VARCHAR(64) NOT NULL DEFAULT 'manual',
  related_sql_id VARCHAR(64),
  layer_results JSON,
  diagnose_status VARCHAR(32) NOT NULL DEFAULT 'healthy',
  stat_period VARCHAR(128)
);

CREATE TABLE IF NOT EXISTS med_index_run_result (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  hospital_id VARCHAR(64) NOT NULL,
  rule_id VARCHAR(64) NOT NULL,
  stat_period VARCHAR(128) NOT NULL,
  result_value DECIMAL(18, 4),
  previous_value DECIMAL(18, 4),
  change_rate DECIMAL(18, 4),
  is_abnormal TINYINT NOT NULL DEFAULT 0,
  run_id VARCHAR(64),
  created_at DATETIME NOT NULL
);
