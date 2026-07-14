CREATE DATABASE IF NOT EXISTS wiki_agent_runtime DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE wiki_agent_runtime;

CREATE TABLE IF NOT EXISTS med_hospital_user (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id VARCHAR(64) NOT NULL UNIQUE,
  account_id VARCHAR(64) NOT NULL UNIQUE,
  hospital_id VARCHAR(64) NOT NULL,
  password_hash VARCHAR(128) NOT NULL,
  password_salt VARCHAR(64) NOT NULL,
  password_iterations INT NOT NULL,
  must_change_password TINYINT NOT NULL DEFAULT 1,
  status VARCHAR(32) NOT NULL DEFAULT 'active',
  failed_attempts INT NOT NULL DEFAULT 0,
  locked_until DATETIME,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  KEY idx_hospital_user_scope (hospital_id, status)
);

CREATE TABLE IF NOT EXISTS med_hospital_user_permission (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id VARCHAR(64) NOT NULL,
  permission_code VARCHAR(64) NOT NULL,
  created_at DATETIME NOT NULL,
  UNIQUE KEY uq_hospital_user_permission (user_id, permission_code)
);

CREATE TABLE IF NOT EXISTS med_hospital_session (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_id VARCHAR(64) NOT NULL UNIQUE,
  user_id VARCHAR(64) NOT NULL,
  token_hash VARCHAR(64) NOT NULL UNIQUE,
  expires_at DATETIME NOT NULL,
  revoked_at DATETIME,
  created_at DATETIME NOT NULL,
  last_seen_at DATETIME NOT NULL,
  KEY idx_hospital_session_user (user_id, expires_at)
);

CREATE TABLE IF NOT EXISTS med_data_access_audit (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  audit_id VARCHAR(64) NOT NULL UNIQUE,
  user_id VARCHAR(64),
  hospital_id VARCHAR(64),
  rule_id VARCHAR(64),
  run_id VARCHAR(64),
  export_id VARCHAR(64),
  action VARCHAR(64) NOT NULL,
  result VARCHAR(32) NOT NULL,
  row_count INT,
  request_id VARCHAR(64),
  reason TEXT,
  created_at DATETIME NOT NULL,
  KEY idx_data_access_audit_scope (hospital_id, created_at)
);

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
  calculation_definition JSON,
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
  custom_calculation_patch JSON,
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

CREATE TABLE IF NOT EXISTS med_table_relation (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  hospital_id VARCHAR(64) NOT NULL,
  db_name VARCHAR(128) NOT NULL,
  left_table VARCHAR(128) NOT NULL,
  left_column VARCHAR(128) NOT NULL,
  right_table VARCHAR(128) NOT NULL,
  right_column VARCHAR(128) NOT NULL,
  join_type VARCHAR(16) NOT NULL,
  relation_source VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'confirmed',
  updated_by VARCHAR(64),
  updated_at DATETIME NOT NULL,
  UNIQUE KEY uk_table_relation (
    hospital_id, db_name, left_table, left_column, right_table, right_column
  )
);

CREATE TABLE IF NOT EXISTS med_indicator_draft (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  draft_id VARCHAR(64) NOT NULL UNIQUE,
  hospital_id VARCHAR(64) NOT NULL,
  base_index_code VARCHAR(64),
  proposed_index_code VARCHAR(64) NOT NULL,
  index_name VARCHAR(128) NOT NULL,
  index_type VARCHAR(64) NOT NULL,
  index_desc TEXT NOT NULL,
  stat_cycle VARCHAR(32) NOT NULL,
  numerator_rule TEXT NOT NULL,
  denominator_rule TEXT NOT NULL,
  filter_rule TEXT,
  exclude_rule TEXT,
  metric_type VARCHAR(32) NOT NULL,
  metadata_requirements JSON NOT NULL,
  field_mapping JSON NOT NULL,
  sql_plan JSON NOT NULL,
  current_sql LONGTEXT,
  sql_params JSON NOT NULL,
  sql_id VARCHAR(64),
  trial_result JSON NOT NULL,
  trial_draft_version INT,
  status VARCHAR(32) NOT NULL,
  current_version INT NOT NULL,
  formal_index_code VARCHAR(64),
  generated_by VARCHAR(64),
  created_by VARCHAR(64) NOT NULL,
  updated_by VARCHAR(64) NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  UNIQUE KEY uk_draft_hospital_code (hospital_id, proposed_index_code),
  KEY idx_draft_hospital_status (hospital_id, status),
  KEY idx_draft_updated (updated_at)
);

CREATE TABLE IF NOT EXISTS med_indicator_draft_version (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  draft_id VARCHAR(64) NOT NULL,
  version INT NOT NULL,
  status VARCHAR(32) NOT NULL,
  snapshot_json JSON NOT NULL,
  change_type VARCHAR(64) NOT NULL,
  oper_user VARCHAR(64) NOT NULL,
  created_at DATETIME NOT NULL,
  UNIQUE KEY uk_draft_version (draft_id, version),
  KEY idx_draft_version_status (draft_id, status)
);

CREATE TABLE IF NOT EXISTS med_index_hospital_defined (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  hospital_id VARCHAR(64) NOT NULL,
  index_code VARCHAR(64) NOT NULL,
  index_name VARCHAR(128) NOT NULL,
  index_type VARCHAR(64) NOT NULL,
  index_desc TEXT NOT NULL,
  stat_cycle VARCHAR(32) NOT NULL,
  numerator_rule TEXT NOT NULL,
  denominator_rule TEXT NOT NULL,
  filter_rule TEXT,
  exclude_rule TEXT,
  field_contract JSON NOT NULL,
  sql_template LONGTEXT NOT NULL,
  rule_params JSON NOT NULL,
  version INT NOT NULL,
  status TINYINT NOT NULL DEFAULT 1,
  approval_status VARCHAR(32) NOT NULL,
  effective_from DATETIME,
  effective_to DATETIME,
  source_draft_id VARCHAR(64) NOT NULL,
  oper_user VARCHAR(64) NOT NULL,
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  UNIQUE KEY uk_hospital_defined_code (hospital_id, index_code),
  KEY idx_hospital_defined_status (hospital_id, status, approval_status)
);

CREATE TABLE IF NOT EXISTS med_index_hospital_defined_version (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  hospital_id VARCHAR(64) NOT NULL,
  index_code VARCHAR(64) NOT NULL,
  version INT NOT NULL,
  snapshot_json JSON NOT NULL,
  source_version INT,
  source_draft_id VARCHAR(64),
  change_type VARCHAR(64) NOT NULL,
  oper_user VARCHAR(64) NOT NULL,
  approver_id VARCHAR(64),
  created_at DATETIME NOT NULL,
  approved_at DATETIME,
  UNIQUE KEY uk_hospital_defined_version (hospital_id, index_code, version),
  KEY idx_hospital_defined_version_status (hospital_id, index_code)
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
  numerator_count BIGINT,
  denominator_count BIGINT,
  run_context_json JSON,
  run_time DATETIME NOT NULL
);

CREATE TABLE IF NOT EXISTS med_indicator_detail_snapshot (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  snapshot_id VARCHAR(64) NOT NULL UNIQUE,
  run_id VARCHAR(64) NOT NULL UNIQUE,
  hospital_id VARCHAR(64) NOT NULL,
  rule_id VARCHAR(64) NOT NULL,
  relative_path VARCHAR(512) NOT NULL,
  file_sha256 VARCHAR(64),
  denominator_count INT,
  numerator_count INT,
  unmatched_count INT,
  column_schema_json JSON,
  status VARCHAR(32) NOT NULL,
  created_by VARCHAR(64) NOT NULL,
  created_at DATETIME NOT NULL,
  expires_at DATETIME NOT NULL,
  error_message TEXT,
  KEY idx_detail_snapshot_scope (hospital_id, expires_at)
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
  created_at DATETIME NOT NULL,
  plan_id VARCHAR(64),
  run_key VARCHAR(255),
  retry_of_result_id BIGINT,
  trigger_type VARCHAR(32),
  stat_start_time DATETIME,
  stat_end_time DATETIME,
  run_status VARCHAR(32),
  no_sample TINYINT NOT NULL DEFAULT 0,
  effective_level VARCHAR(32),
  national_version VARCHAR(64),
  hospital_version INT,
  data_source VARCHAR(128),
  duration_ms INT,
  error_code VARCHAR(128),
  error_message TEXT,
  mom_baseline_result_id BIGINT,
  mom_change_rate DECIMAL(18, 4),
  yoy_baseline_result_id BIGINT,
  yoy_change_rate DECIMAL(18, 4),
  wave_status VARCHAR(64),
  UNIQUE KEY uq_med_index_run_result_run_key (run_key),
  INDEX idx_run_result_scope (hospital_id, rule_id, stat_start_time, stat_end_time)
);

CREATE TABLE IF NOT EXISTS med_indicator_run_plan (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  plan_id VARCHAR(64) NOT NULL UNIQUE,
  hospital_id VARCHAR(64) NOT NULL,
  rule_id VARCHAR(64) NOT NULL,
  plan_name VARCHAR(128) NOT NULL,
  frequency VARCHAR(32) NOT NULL,
  run_time VARCHAR(8) NOT NULL DEFAULT '02:00',
  day_of_month INT NOT NULL DEFAULT 1,
  timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai',
  mom_enabled TINYINT NOT NULL DEFAULT 1,
  mom_threshold_pct DECIMAL(10, 2) NOT NULL DEFAULT 20.00,
  yoy_enabled TINYINT NOT NULL DEFAULT 1,
  yoy_threshold_pct DECIMAL(10, 2) NOT NULL DEFAULT 30.00,
  status VARCHAR(32) NOT NULL DEFAULT 'enabled',
  next_run_at DATETIME,
  last_run_at DATETIME,
  locked_until DATETIME,
  locked_by VARCHAR(128) NOT NULL DEFAULT '',
  created_by VARCHAR(64) NOT NULL DEFAULT 'admin',
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  UNIQUE KEY uq_monitor_plan (hospital_id, rule_id, plan_name),
  INDEX idx_monitor_plan_status (status),
  INDEX idx_monitor_plan_next (next_run_at)
);

CREATE TABLE IF NOT EXISTS med_indicator_alert (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  alert_id VARCHAR(64) NOT NULL UNIQUE,
  hospital_id VARCHAR(64) NOT NULL,
  rule_id VARCHAR(64) NOT NULL,
  plan_id VARCHAR(64),
  result_id BIGINT NOT NULL,
  alert_type VARCHAR(32) NOT NULL,
  alert_level VARCHAR(16) NOT NULL,
  conclusion_code VARCHAR(64) NOT NULL,
  current_value DECIMAL(18, 4),
  mom_value DECIMAL(18, 4),
  mom_change_rate DECIMAL(18, 4),
  yoy_value DECIMAL(18, 4),
  yoy_change_rate DECIMAL(18, 4),
  diagnose_status VARCHAR(32) NOT NULL DEFAULT 'pending',
  diagnose_report_id VARCHAR(64),
  status VARCHAR(32) NOT NULL DEFAULT 'open',
  acknowledged_by VARCHAR(64),
  acknowledged_at DATETIME,
  closed_at DATETIME,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  UNIQUE KEY uq_monitor_alert (result_id, alert_type, conclusion_code),
  INDEX idx_monitor_alert_hospital (hospital_id),
  INDEX idx_monitor_alert_status (status)
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

CREATE TABLE IF NOT EXISTS med_term_concept (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  concept_code VARCHAR(96) NOT NULL UNIQUE,
  canonical_name VARCHAR(255) NOT NULL,
  concept_type VARCHAR(32) NOT NULL,
  definition TEXT NOT NULL,
  standard_code VARCHAR(128),
  source_level VARCHAR(32) NOT NULL,
  source_reference TEXT NOT NULL,
  version INT NOT NULL,
  status VARCHAR(32) NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL
);

CREATE TABLE IF NOT EXISTS med_term_alias (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  hospital_id VARCHAR(64) NOT NULL DEFAULT '',
  concept_code VARCHAR(96) NOT NULL,
  alias_text VARCHAR(255) NOT NULL,
  relation_type VARCHAR(32) NOT NULL,
  retrieval_enabled TINYINT NOT NULL DEFAULT 1,
  sql_safe TINYINT NOT NULL DEFAULT 0,
  ambiguity_group VARCHAR(96),
  source_reference TEXT NOT NULL,
  approval_status VARCHAR(32) NOT NULL,
  version INT NOT NULL,
  created_by VARCHAR(64),
  approved_by VARCHAR(64),
  created_at DATETIME NOT NULL,
  approved_at DATETIME,
  UNIQUE KEY uk_term_alias_scope (hospital_id, concept_code, alias_text, version),
  INDEX idx_term_alias_text (alias_text)
);

CREATE TABLE IF NOT EXISTS med_term_rule_link (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  concept_code VARCHAR(96) NOT NULL,
  index_code VARCHAR(64) NOT NULL,
  usage_section VARCHAR(32) NOT NULL,
  business_field_key VARCHAR(128),
  source_reference TEXT NOT NULL,
  version INT NOT NULL,
  UNIQUE KEY uk_term_rule_link (concept_code, index_code, usage_section, version),
  INDEX idx_term_rule_code (index_code)
);

CREATE TABLE IF NOT EXISTS med_hospital_term_mapping (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  hospital_id VARCHAR(64) NOT NULL,
  concept_code VARCHAR(96) NOT NULL,
  code_system VARCHAR(64) NOT NULL,
  local_code VARCHAR(128) NOT NULL DEFAULT '',
  local_name VARCHAR(255) NOT NULL,
  local_value VARCHAR(255) NOT NULL,
  approval_status VARCHAR(32) NOT NULL,
  effective_from DATETIME,
  effective_to DATETIME,
  version INT NOT NULL,
  created_by VARCHAR(64),
  approved_by VARCHAR(64),
  created_at DATETIME NOT NULL,
  approved_at DATETIME,
  UNIQUE KEY uk_hospital_term_current
    (hospital_id, concept_code, code_system, local_code, version),
  INDEX idx_hospital_term_active (hospital_id, approval_status)
);

CREATE TABLE IF NOT EXISTS med_hospital_term_mapping_version (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  version_id VARCHAR(64) NOT NULL UNIQUE,
  hospital_id VARCHAR(64) NOT NULL,
  concept_code VARCHAR(96) NOT NULL,
  version INT NOT NULL,
  snapshot_json JSON NOT NULL,
  change_type VARCHAR(64) NOT NULL,
  oper_user VARCHAR(64),
  approver_id VARCHAR(64),
  created_at DATETIME NOT NULL,
  approved_at DATETIME,
  UNIQUE KEY uk_hospital_term_version (hospital_id, concept_code, version)
);

CREATE TABLE IF NOT EXISTS med_term_release (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  release_id VARCHAR(64) NOT NULL UNIQUE,
  version INT NOT NULL UNIQUE,
  status VARCHAR(32) NOT NULL,
  checksum VARCHAR(64) NOT NULL UNIQUE,
  snapshot_json JSON NOT NULL,
  change_summary TEXT NOT NULL,
  published_by VARCHAR(64) NOT NULL,
  published_at DATETIME NOT NULL
);

CREATE TABLE IF NOT EXISTS med_term_audit_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  action VARCHAR(64) NOT NULL,
  object_type VARCHAR(64) NOT NULL,
  object_id VARCHAR(128) NOT NULL,
  hospital_id VARCHAR(64),
  version VARCHAR(64),
  actor_id VARCHAR(64) NOT NULL,
  detail_json JSON NOT NULL,
  created_at DATETIME NOT NULL
);
