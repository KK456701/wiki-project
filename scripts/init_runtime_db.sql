CREATE DATABASE IF NOT EXISTS wiki_agent_runtime DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE wiki_agent_runtime;

CREATE TABLE IF NOT EXISTS med_index_standard (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  index_code VARCHAR(64) NOT NULL,
  index_name VARCHAR(128) NOT NULL,
  index_type VARCHAR(32) NOT NULL,
  index_desc TEXT NOT NULL,
  stat_cycle VARCHAR(32) NOT NULL DEFAULT 'month',
  numerator_rule TEXT NOT NULL,
  denominator_rule TEXT NOT NULL,
  filter_rule TEXT,
  exclude_rule TEXT,
  rely_table_field JSON NOT NULL,
  standard_sql LONGTEXT NOT NULL,
  rule_params JSON NOT NULL,
  source_path VARCHAR(512),
  version VARCHAR(64) NOT NULL,
  status TINYINT NOT NULL DEFAULT 1,
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  UNIQUE KEY uk_standard_code (index_code),
  KEY idx_standard_name (index_name),
  KEY idx_standard_status (status)
);

CREATE TABLE IF NOT EXISTS med_index_hospital_custom (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  hospital_id VARCHAR(64) NOT NULL,
  index_code VARCHAR(64) NOT NULL,
  custom_numerator TEXT,
  custom_denominator TEXT,
  custom_filter TEXT,
  exclude_rule TEXT,
  custom_params JSON NOT NULL,
  custom_sql LONGTEXT,
  version INT NOT NULL,
  status TINYINT NOT NULL DEFAULT 1,
  approval_status VARCHAR(32) NOT NULL,
  effective_from DATETIME,
  effective_to DATETIME,
  oper_user VARCHAR(64),
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  UNIQUE KEY uk_hospital_index (hospital_id, index_code),
  KEY idx_hospital_custom_status (hospital_id, status, approval_status)
);

CREATE TABLE IF NOT EXISTS med_index_hospital_custom_version (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  change_id VARCHAR(64) NOT NULL,
  hospital_id VARCHAR(64) NOT NULL,
  index_code VARCHAR(64) NOT NULL,
  version INT NOT NULL,
  approval_status VARCHAR(32) NOT NULL,
  snapshot_json JSON NOT NULL,
  source_version INT,
  change_type VARCHAR(64) NOT NULL,
  oper_user VARCHAR(64),
  approver_id VARCHAR(64),
  created_at DATETIME NOT NULL,
  approved_at DATETIME,
  UNIQUE KEY uk_custom_change (change_id),
  UNIQUE KEY uk_hospital_index_version (hospital_id, index_code, version),
  KEY idx_custom_version_status (hospital_id, index_code, approval_status)
);

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

CREATE TABLE IF NOT EXISTS med_metadata_snapshot (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  hospital_id VARCHAR(64) NOT NULL,
  db_name VARCHAR(128) NOT NULL,
  metadata_source VARCHAR(32) NOT NULL,
  sync_batch_id VARCHAR(64) NOT NULL,
  snapshot_json JSON NOT NULL,
  created_at DATETIME NOT NULL,
  KEY idx_snapshot_batch (hospital_id, db_name, sync_batch_id)
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

CREATE TABLE IF NOT EXISTS med_agent_trace (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  trace_id VARCHAR(64) NOT NULL UNIQUE,
  session_id VARCHAR(128),
  hospital_id VARCHAR(64),
  user_id VARCHAR(128),
  user_query TEXT,
  intent VARCHAR(64),
  final_status VARCHAR(32),
  final_answer_summary TEXT,
  error_count INT DEFAULT 0,
  fallback_count INT DEFAULT 0,
  started_at DATETIME NOT NULL,
  ended_at DATETIME,
  duration_ms INT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS med_agent_trace_node (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  trace_id VARCHAR(64) NOT NULL,
  node_id VARCHAR(64) NOT NULL,
  node_name VARCHAR(128) NOT NULL,
  node_type VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,
  input_summary TEXT,
  output_summary TEXT,
  error_code VARCHAR(128),
  error_message TEXT,
  tool_name VARCHAR(128),
  db_source VARCHAR(128),
  sql_id VARCHAR(64),
  run_id VARCHAR(64),
  rule_id VARCHAR(64),
  llm_model VARCHAR(128),
  started_at DATETIME NOT NULL,
  ended_at DATETIME,
  duration_ms INT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_trace_node_trace_id (trace_id),
  INDEX idx_trace_node_status (status),
  INDEX idx_trace_node_rule_id (rule_id)
);

CREATE TABLE IF NOT EXISTS med_recovery_task (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_id VARCHAR(64) NOT NULL UNIQUE,
  task_type VARCHAR(64) NOT NULL,
  task_name VARCHAR(255) NOT NULL,
  status VARCHAR(32) NOT NULL,
  current_step VARCHAR(128),
  trace_id VARCHAR(64),
  request_id VARCHAR(64),
  hospital_id VARCHAR(64),
  rule_id VARCHAR(64),
  payload_json TEXT,
  result_json TEXT,
  error_message TEXT,
  retry_count INT DEFAULT 0,
  recoverable_action VARCHAR(64),
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  completed_at DATETIME,
  INDEX idx_recovery_status (status),
  INDEX idx_recovery_type (task_type),
  INDEX idx_recovery_trace (trace_id)
);
