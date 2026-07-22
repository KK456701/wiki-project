-- Java 单运行时的 SQLite 参考结构。实际启动时由 Spring Boot schema initializer 幂等维护。

CREATE TABLE IF NOT EXISTS med_hospital_user (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id VARCHAR(64) NOT NULL UNIQUE,
  account_id VARCHAR(64) NOT NULL UNIQUE,
  hospital_id VARCHAR(64) NOT NULL,
  password_hash VARCHAR(128) NOT NULL,
  password_salt VARCHAR(64) NOT NULL,
  password_iterations INT NOT NULL,
  must_change_password INTEGER NOT NULL DEFAULT 1,
  status VARCHAR(32) NOT NULL DEFAULT 'active',
  failed_attempts INT NOT NULL DEFAULT 0,
  locked_until TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS med_hospital_user_permission (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id VARCHAR(64) NOT NULL,
  permission_code VARCHAR(64) NOT NULL,
  created_at TEXT NOT NULL,
  UNIQUE (user_id, permission_code)
);

CREATE TABLE IF NOT EXISTS med_hospital_session (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  session_id VARCHAR(64) NOT NULL UNIQUE,
  user_id VARCHAR(64) NOT NULL,
  token_hash VARCHAR(64) NOT NULL UNIQUE,
  expires_at TEXT NOT NULL,
  revoked_at TEXT,
  created_at TEXT NOT NULL,
  last_seen_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS med_data_access_audit (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
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
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS med_index_standard (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  index_code VARCHAR(64) NOT NULL,
  index_name VARCHAR(128) NOT NULL,
  index_type VARCHAR(32) NOT NULL,
  index_desc TEXT NOT NULL,
  stat_cycle VARCHAR(32) NOT NULL DEFAULT 'month',
  numerator_rule TEXT NOT NULL,
  denominator_rule TEXT NOT NULL,
  filter_rule TEXT,
  exclude_rule TEXT,
  rely_table_field TEXT NOT NULL,
  calculation_definition TEXT,
  standard_sql TEXT NOT NULL,
  rule_params TEXT NOT NULL,
  source_path VARCHAR(512),
  version VARCHAR(64) NOT NULL,
  status INTEGER NOT NULL DEFAULT 1,
  create_time TEXT NOT NULL,
  update_time TEXT NOT NULL,
  UNIQUE (index_code)
);

CREATE TABLE IF NOT EXISTS med_index_hospital_custom (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  hospital_id VARCHAR(64) NOT NULL,
  index_code VARCHAR(64) NOT NULL,
  custom_numerator TEXT,
  custom_denominator TEXT,
  custom_filter TEXT,
  exclude_rule TEXT,
  custom_params TEXT NOT NULL,
  custom_calculation_patch TEXT,
  custom_sql TEXT,
  version INT NOT NULL,
  status INTEGER NOT NULL DEFAULT 1,
  approval_status VARCHAR(32) NOT NULL,
  effective_from TEXT,
  effective_to TEXT,
  oper_user VARCHAR(64),
  create_time TEXT NOT NULL,
  update_time TEXT NOT NULL,
  UNIQUE (hospital_id, index_code)
);

CREATE TABLE IF NOT EXISTS med_index_hospital_custom_version (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  change_id VARCHAR(64) NOT NULL,
  hospital_id VARCHAR(64) NOT NULL,
  index_code VARCHAR(64) NOT NULL,
  version INT NOT NULL,
  approval_status VARCHAR(32) NOT NULL,
  snapshot_json TEXT NOT NULL,
  source_version INT,
  change_type VARCHAR(64) NOT NULL,
  oper_user VARCHAR(64),
  approver_id VARCHAR(64),
  created_at TEXT NOT NULL,
  approved_at TEXT,
  UNIQUE (change_id),
  UNIQUE (hospital_id, index_code, version)
);

CREATE TABLE IF NOT EXISTS med_metadata_table (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  hospital_id VARCHAR(64) NOT NULL,
  db_name VARCHAR(128) NOT NULL,
  table_name VARCHAR(128) NOT NULL,
  table_comment TEXT,
  table_type VARCHAR(32),
  sync_batch_id VARCHAR(64) NOT NULL,
  sync_time TEXT NOT NULL,
  UNIQUE (hospital_id, db_name, table_name)
);

CREATE TABLE IF NOT EXISTS med_metadata_column (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
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
  sync_time TEXT NOT NULL,
  UNIQUE (hospital_id, db_name, table_name, column_name)
);

CREATE TABLE IF NOT EXISTS med_metadata_sync_log (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  hospital_id VARCHAR(64) NOT NULL,
  db_name VARCHAR(128) NOT NULL,
  table_name VARCHAR(128),
  field_name VARCHAR(128),
  change_type VARCHAR(32),
  change_desc TEXT,
  sync_batch_id VARCHAR(64) NOT NULL,
  sync_time TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS med_metadata_snapshot (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  hospital_id VARCHAR(64) NOT NULL,
  db_name VARCHAR(128) NOT NULL,
  metadata_source VARCHAR(32) NOT NULL,
  sync_batch_id VARCHAR(64) NOT NULL,
  snapshot_json TEXT NOT NULL,
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS med_field_mapping (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  hospital_id VARCHAR(64) NOT NULL,
  rule_id VARCHAR(64) NOT NULL,
  business_field VARCHAR(128) NOT NULL,
  db_name VARCHAR(128) NOT NULL,
  table_name VARCHAR(128) NOT NULL,
  column_name VARCHAR(128) NOT NULL,
  data_type VARCHAR(64),
  status VARCHAR(32) NOT NULL DEFAULT 'confirmed',
  updated_by VARCHAR(64),
  updated_at TEXT NOT NULL,
  UNIQUE (hospital_id, rule_id, business_field)
);

CREATE TABLE IF NOT EXISTS med_table_relation (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
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
  updated_at TEXT NOT NULL,
  UNIQUE ( hospital_id, db_name, left_table, left_column, right_table, right_column )
);

CREATE TABLE IF NOT EXISTS med_metadata_export_scope (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  hospital_id VARCHAR(64) NOT NULL,
  db_name VARCHAR(128) NOT NULL,
  table_name VARCHAR(128) NOT NULL,
  column_name VARCHAR(128) NOT NULL,
  selected_by VARCHAR(64) NOT NULL,
  updated_at TEXT NOT NULL,
  UNIQUE ( hospital_id, db_name, table_name, column_name )
);

CREATE TABLE IF NOT EXISTS med_company_package_import (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  import_id VARCHAR(64) NOT NULL UNIQUE,
  package_id VARCHAR(64) NOT NULL UNIQUE,
  release_id VARCHAR(64),
  format_version VARCHAR(32) NOT NULL,
  package_checksum CHAR(64) NOT NULL,
  signer_key_id VARCHAR(96),
  signature_status VARCHAR(32) NOT NULL,
  compatibility_status VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL,
  manifest_json TEXT NOT NULL,
  compatibility_json TEXT NOT NULL,
  imported_by VARCHAR(64) NOT NULL,
  imported_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS med_company_package_item (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  import_id VARCHAR(64) NOT NULL,
  item_path VARCHAR(512) NOT NULL,
  item_type VARCHAR(32) NOT NULL,
  rule_id VARCHAR(64),
  payload_json TEXT NOT NULL,
  UNIQUE (import_id, item_path)
);

CREATE TABLE IF NOT EXISTS med_package_audit (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  direction VARCHAR(32) NOT NULL,
  package_id VARCHAR(64) NOT NULL,
  hospital_id VARCHAR(64),
  event_type VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL,
  actor_id VARCHAR(64) NOT NULL,
  detail_json TEXT NOT NULL,
  created_at TEXT NOT NULL,
  message TEXT
);

CREATE TABLE IF NOT EXISTS med_indicator_draft (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
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
  metadata_requirements TEXT NOT NULL,
  field_mapping TEXT NOT NULL,
  sql_plan TEXT NOT NULL,
  current_sql TEXT,
  sql_params TEXT NOT NULL,
  sql_id VARCHAR(64),
  trial_result TEXT NOT NULL,
  trial_draft_version INT,
  status VARCHAR(32) NOT NULL,
  current_version INT NOT NULL,
  formal_index_code VARCHAR(64),
  generated_by VARCHAR(64),
  created_by VARCHAR(64) NOT NULL,
  updated_by VARCHAR(64) NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  UNIQUE (hospital_id, proposed_index_code)
);

CREATE TABLE IF NOT EXISTS med_indicator_draft_version (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  draft_id VARCHAR(64) NOT NULL,
  version INT NOT NULL,
  status VARCHAR(32) NOT NULL,
  snapshot_json TEXT NOT NULL,
  change_type VARCHAR(64) NOT NULL,
  oper_user VARCHAR(64) NOT NULL,
  created_at TEXT NOT NULL,
  UNIQUE (draft_id, version)
);

CREATE TABLE IF NOT EXISTS med_index_hospital_defined (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
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
  field_contract TEXT NOT NULL,
  sql_template TEXT NOT NULL,
  rule_params TEXT NOT NULL,
  version INT NOT NULL,
  status INTEGER NOT NULL DEFAULT 1,
  approval_status VARCHAR(32) NOT NULL,
  effective_from TEXT,
  effective_to TEXT,
  source_draft_id VARCHAR(64) NOT NULL,
  oper_user VARCHAR(64) NOT NULL,
  create_time TEXT NOT NULL,
  update_time TEXT NOT NULL,
  UNIQUE (hospital_id, index_code)
);

CREATE TABLE IF NOT EXISTS med_index_hospital_defined_version (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  hospital_id VARCHAR(64) NOT NULL,
  index_code VARCHAR(64) NOT NULL,
  version INT NOT NULL,
  snapshot_json TEXT NOT NULL,
  source_version INT,
  source_draft_id VARCHAR(64),
  change_type VARCHAR(64) NOT NULL,
  oper_user VARCHAR(64) NOT NULL,
  approver_id VARCHAR(64),
  created_at TEXT NOT NULL,
  approved_at TEXT,
  UNIQUE (hospital_id, index_code, version)
);

CREATE TABLE IF NOT EXISTS med_generated_sql (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  sql_id VARCHAR(64) NOT NULL UNIQUE,
  hospital_id VARCHAR(64) NOT NULL,
  rule_id VARCHAR(64) NOT NULL,
  dialect VARCHAR(32) NOT NULL,
  sql_text TEXT NOT NULL,
  sql_status VARCHAR(32) NOT NULL,
  validation_message TEXT,
  generated_by VARCHAR(64),
  generated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS med_agent_sql_object (
  sql_id VARCHAR(80) PRIMARY KEY,
  hospital_id VARCHAR(128) NOT NULL,
  user_id VARCHAR(128) NOT NULL,
  session_id VARCHAR(128) NOT NULL,
  rule_id VARCHAR(128) NOT NULL,
  dialect VARCHAR(32) NOT NULL,
  sql_text TEXT NOT NULL,
  params_json TEXT NOT NULL,
  stat_start VARCHAR(32) NOT NULL,
  stat_end VARCHAR(32) NOT NULL,
  context_snapshot_json TEXT NOT NULL,
  context_digest VARCHAR(64) NOT NULL,
  validation_status VARCHAR(32) NOT NULL,
  validation_message TEXT NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  expires_at VARCHAR(40) NOT NULL,
  db_source_id VARCHAR(128)
);

CREATE TABLE IF NOT EXISTS med_sql_run_log (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  run_id VARCHAR(64) NOT NULL UNIQUE,
  sql_id VARCHAR(64),
  hospital_id VARCHAR(64) NOT NULL,
  rule_id VARCHAR(64) NOT NULL,
  stat_start_time TEXT,
  stat_end_time TEXT,
  run_status VARCHAR(32) NOT NULL,
  result_value REAL,
  error_message TEXT,
  duration_ms INT,
  run_by VARCHAR(64),
  numerator_count BIGINT,
  denominator_count BIGINT,
  run_context_json TEXT,
  run_time TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS med_indicator_detail_snapshot (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  snapshot_id VARCHAR(64) NOT NULL UNIQUE,
  run_id VARCHAR(64) NOT NULL UNIQUE,
  hospital_id VARCHAR(64) NOT NULL,
  rule_id VARCHAR(64) NOT NULL,
  relative_path VARCHAR(512) NOT NULL,
  file_sha256 VARCHAR(64),
  denominator_count INT,
  numerator_count INT,
  unmatched_count INT,
  column_schema_json TEXT,
  status VARCHAR(32) NOT NULL,
  created_by VARCHAR(64) NOT NULL,
  created_at TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  error_message TEXT
);

CREATE TABLE IF NOT EXISTS med_indicator_export (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  export_id VARCHAR(64) NOT NULL UNIQUE,
  snapshot_id VARCHAR(64) NOT NULL,
  run_id VARCHAR(64) NOT NULL,
  hospital_id VARCHAR(64) NOT NULL,
  rule_id VARCHAR(64) NOT NULL,
  relative_path VARCHAR(512) NOT NULL,
  file_name VARCHAR(255) NOT NULL,
  file_sha256 VARCHAR(64) NULL,
  status VARCHAR(32) NOT NULL,
  row_count INT NOT NULL,
  created_by VARCHAR(64) NOT NULL,
  created_at TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  download_count INT NOT NULL DEFAULT 0,
  last_downloaded_at TEXT NULL,
  error_message TEXT NULL
);

CREATE TABLE IF NOT EXISTS med_index_diagnose_report (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  report_id VARCHAR(64) NOT NULL UNIQUE,
  hospital_id VARCHAR(64) NOT NULL,
  rule_id VARCHAR(64) NOT NULL,
  diagnose_type VARCHAR(32) NOT NULL,
  problem_detail TEXT,
  repair_suggest TEXT,
  repair_sql TEXT,
  diagnose_time TEXT NOT NULL,
  status INTEGER NOT NULL DEFAULT 0,
  trigger_type VARCHAR(64) NOT NULL DEFAULT 'manual',
  related_sql_id VARCHAR(64),
  layer_results TEXT,
  diagnose_status VARCHAR(32) NOT NULL DEFAULT 'healthy',
  stat_period VARCHAR(128)
);

CREATE TABLE IF NOT EXISTS med_index_run_result (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  hospital_id VARCHAR(64) NOT NULL,
  rule_id VARCHAR(64) NOT NULL,
  stat_period VARCHAR(128) NOT NULL,
  result_value REAL,
  previous_value REAL,
  change_rate REAL,
  is_abnormal INTEGER NOT NULL DEFAULT 0,
  run_id VARCHAR(64),
  created_at TEXT NOT NULL,
  plan_id VARCHAR(64),
  run_key VARCHAR(255),
  retry_of_result_id BIGINT,
  trigger_type VARCHAR(32),
  stat_start_time TEXT,
  stat_end_time TEXT,
  run_status VARCHAR(32),
  no_sample INTEGER NOT NULL DEFAULT 0,
  effective_level VARCHAR(32),
  national_version VARCHAR(64),
  hospital_version INT,
  data_source VARCHAR(128),
  duration_ms INT,
  error_code VARCHAR(128),
  error_message TEXT,
  mom_baseline_result_id BIGINT,
  mom_change_rate REAL,
  yoy_baseline_result_id BIGINT,
  yoy_change_rate REAL,
  wave_status VARCHAR(64),
  UNIQUE (run_key)
);

CREATE TABLE IF NOT EXISTS med_indicator_run_plan (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  plan_id VARCHAR(64) NOT NULL UNIQUE,
  hospital_id VARCHAR(64) NOT NULL,
  rule_id VARCHAR(64) NOT NULL,
  plan_name VARCHAR(128) NOT NULL,
  frequency VARCHAR(32) NOT NULL,
  run_time VARCHAR(8) NOT NULL DEFAULT '02:00',
  day_of_month INT NOT NULL DEFAULT 1,
  timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai',
  mom_enabled INTEGER NOT NULL DEFAULT 1,
  mom_threshold_pct REAL NOT NULL DEFAULT 20.00,
  yoy_enabled INTEGER NOT NULL DEFAULT 1,
  yoy_threshold_pct REAL NOT NULL DEFAULT 30.00,
  status VARCHAR(32) NOT NULL DEFAULT 'enabled',
  next_run_at TEXT,
  last_run_at TEXT,
  locked_until TEXT,
  locked_by VARCHAR(128) NOT NULL DEFAULT '',
  created_by VARCHAR(64) NOT NULL DEFAULT 'admin',
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  UNIQUE (hospital_id, rule_id, plan_name)
);

CREATE TABLE IF NOT EXISTS med_indicator_alert (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  alert_id VARCHAR(64) NOT NULL UNIQUE,
  hospital_id VARCHAR(64) NOT NULL,
  rule_id VARCHAR(64) NOT NULL,
  plan_id VARCHAR(64),
  result_id BIGINT NOT NULL,
  alert_type VARCHAR(32) NOT NULL,
  alert_level VARCHAR(16) NOT NULL,
  conclusion_code VARCHAR(64) NOT NULL,
  current_value REAL,
  mom_value REAL,
  mom_change_rate REAL,
  yoy_value REAL,
  yoy_change_rate REAL,
  diagnose_status VARCHAR(32) NOT NULL DEFAULT 'pending',
  diagnose_report_id VARCHAR(64),
  status VARCHAR(32) NOT NULL DEFAULT 'open',
  acknowledged_by VARCHAR(64),
  acknowledged_at TEXT,
  closed_at TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  UNIQUE (result_id, alert_type, conclusion_code)
);

CREATE TABLE IF NOT EXISTS med_agent_trace (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
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
  started_at TEXT NOT NULL,
  ended_at TEXT,
  duration_ms INT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS med_agent_trace_node (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
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
  started_at TEXT NOT NULL,
  ended_at TEXT,
  duration_ms INT,
  parent_node_id VARCHAR(80),
  subtask_id VARCHAR(128),
  sequence INT,
  started_offset_ms INT,
  exclusive_duration_ms INT,
  capability VARCHAR(80),
  model_id VARCHAR(128),
  failure_class VARCHAR(80),
  input_tokens INT,
  output_tokens INT,
  cache_reused INTEGER DEFAULT 0,
  retry_count INT DEFAULT 0,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS med_agent_evidence (
  evidence_id VARCHAR(80) PRIMARY KEY,
  schema_version VARCHAR(40) NOT NULL,
  trace_id VARCHAR(128) NOT NULL,
  subtask_id VARCHAR(128) NOT NULL,
  fact_type VARCHAR(80) NOT NULL,
  hospital_id VARCHAR(128) NOT NULL,
  rule_id VARCHAR(128),
  rule_version VARCHAR(80),
  stat_start VARCHAR(40),
  stat_end VARCHAR(40),
  source_tool VARCHAR(80) NOT NULL,
  source_object_id VARCHAR(128),
  input_fingerprint VARCHAR(64) NOT NULL,
  result_fingerprint VARCHAR(64) NOT NULL,
  confidentiality VARCHAR(32) NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  expires_at VARCHAR(40),
  payload_ref VARCHAR(255),
  safe_payload_json TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS med_agent_evidence_verification (
  verification_id VARCHAR(80) PRIMARY KEY,
  schema_version VARCHAR(40) NOT NULL,
  evidence_id VARCHAR(80) NOT NULL,
  trace_id VARCHAR(128) NOT NULL,
  subtask_id VARCHAR(128) NOT NULL,
  hospital_id VARCHAR(128) NOT NULL,
  verifier_version VARCHAR(80) NOT NULL,
  status VARCHAR(20) NOT NULL,
  code VARCHAR(80) NOT NULL,
  message TEXT NOT NULL,
  verified_at VARCHAR(40) NOT NULL
);

CREATE TABLE IF NOT EXISTS med_recovery_task (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
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
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  completed_at TEXT
);

CREATE TABLE IF NOT EXISTS med_term_concept (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  concept_code VARCHAR(96) NOT NULL UNIQUE,
  canonical_name VARCHAR(255) NOT NULL,
  concept_type VARCHAR(32) NOT NULL,
  definition TEXT NOT NULL,
  standard_code VARCHAR(128),
  source_level VARCHAR(32) NOT NULL,
  source_reference TEXT NOT NULL,
  version INT NOT NULL,
  status VARCHAR(32) NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS med_term_alias (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  hospital_id VARCHAR(64) NOT NULL DEFAULT '',
  concept_code VARCHAR(96) NOT NULL,
  alias_text VARCHAR(255) NOT NULL,
  relation_type VARCHAR(32) NOT NULL,
  retrieval_enabled INTEGER NOT NULL DEFAULT 1,
  sql_safe INTEGER NOT NULL DEFAULT 0,
  ambiguity_group VARCHAR(96),
  source_reference TEXT NOT NULL,
  approval_status VARCHAR(32) NOT NULL,
  version INT NOT NULL,
  created_by VARCHAR(64),
  approved_by VARCHAR(64),
  created_at TEXT NOT NULL,
  approved_at TEXT,
  UNIQUE (hospital_id, concept_code, alias_text, version)
);

CREATE TABLE IF NOT EXISTS med_term_rule_link (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  concept_code VARCHAR(96) NOT NULL,
  index_code VARCHAR(64) NOT NULL,
  usage_section VARCHAR(32) NOT NULL,
  business_field_key VARCHAR(128),
  source_reference TEXT NOT NULL,
  version INT NOT NULL,
  UNIQUE (concept_code, index_code, usage_section, version)
);

CREATE TABLE IF NOT EXISTS med_hospital_term_mapping (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  hospital_id VARCHAR(64) NOT NULL,
  concept_code VARCHAR(96) NOT NULL,
  code_system VARCHAR(64) NOT NULL,
  local_code VARCHAR(128) NOT NULL DEFAULT '',
  local_name VARCHAR(255) NOT NULL,
  local_value VARCHAR(255) NOT NULL,
  approval_status VARCHAR(32) NOT NULL,
  effective_from TEXT,
  effective_to TEXT,
  version INT NOT NULL,
  created_by VARCHAR(64),
  approved_by VARCHAR(64),
  created_at TEXT NOT NULL,
  approved_at TEXT,
  UNIQUE (hospital_id, concept_code, code_system, local_code, version)
);

CREATE TABLE IF NOT EXISTS med_hospital_term_mapping_version (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  version_id VARCHAR(64) NOT NULL UNIQUE,
  hospital_id VARCHAR(64) NOT NULL,
  concept_code VARCHAR(96) NOT NULL,
  version INT NOT NULL,
  snapshot_json TEXT NOT NULL,
  change_type VARCHAR(64) NOT NULL,
  oper_user VARCHAR(64),
  approver_id VARCHAR(64),
  created_at TEXT NOT NULL,
  approved_at TEXT,
  UNIQUE (hospital_id, concept_code, version)
);

CREATE TABLE IF NOT EXISTS med_term_release (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  release_id VARCHAR(64) NOT NULL UNIQUE,
  version INT NOT NULL UNIQUE,
  status VARCHAR(32) NOT NULL,
  checksum VARCHAR(64) NOT NULL UNIQUE,
  snapshot_json TEXT NOT NULL,
  change_summary TEXT NOT NULL,
  published_by VARCHAR(64) NOT NULL,
  published_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS med_term_audit_log (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  action VARCHAR(64) NOT NULL,
  object_type VARCHAR(64) NOT NULL,
  object_id VARCHAR(128) NOT NULL,
  hospital_id VARCHAR(64),
  version VARCHAR(64),
  actor_id VARCHAR(64) NOT NULL,
  detail_json TEXT NOT NULL,
  created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_hospital_user_scope ON med_hospital_user (hospital_id, status);
CREATE INDEX IF NOT EXISTS idx_hospital_session_user ON med_hospital_session (user_id, expires_at);
CREATE INDEX IF NOT EXISTS idx_data_access_audit_scope ON med_data_access_audit (hospital_id, created_at);
CREATE INDEX IF NOT EXISTS idx_standard_name ON med_index_standard (index_name);
CREATE INDEX IF NOT EXISTS idx_standard_status ON med_index_standard (status);
CREATE INDEX IF NOT EXISTS idx_hospital_custom_status ON med_index_hospital_custom (hospital_id, status, approval_status);
CREATE INDEX IF NOT EXISTS idx_custom_version_status ON med_index_hospital_custom_version (hospital_id, index_code, approval_status);
CREATE INDEX IF NOT EXISTS idx_snapshot_batch ON med_metadata_snapshot (hospital_id, db_name, sync_batch_id);
CREATE INDEX IF NOT EXISTS idx_company_package_import_status ON med_company_package_import (status, imported_at);
CREATE INDEX IF NOT EXISTS idx_company_package_item_rule ON med_company_package_item (rule_id);
CREATE INDEX IF NOT EXISTS idx_package_audit_package ON med_package_audit (package_id, created_at);
CREATE INDEX IF NOT EXISTS idx_draft_hospital_status ON med_indicator_draft (hospital_id, status);
CREATE INDEX IF NOT EXISTS idx_draft_updated ON med_indicator_draft (updated_at);
CREATE INDEX IF NOT EXISTS idx_draft_version_status ON med_indicator_draft_version (draft_id, status);
CREATE INDEX IF NOT EXISTS idx_hospital_defined_status ON med_index_hospital_defined (hospital_id, status, approval_status);
CREATE INDEX IF NOT EXISTS idx_hospital_defined_version_status ON med_index_hospital_defined_version (hospital_id, index_code);
CREATE INDEX IF NOT EXISTS ix_agent_sql_hospital_expiry ON med_agent_sql_object (hospital_id, expires_at);
CREATE INDEX IF NOT EXISTS ix_agent_sql_session_status ON med_agent_sql_object (session_id, validation_status);
CREATE INDEX IF NOT EXISTS idx_detail_snapshot_scope ON med_indicator_detail_snapshot (hospital_id, expires_at);
CREATE INDEX IF NOT EXISTS idx_indicator_export_scope ON med_indicator_export (hospital_id, expires_at);
CREATE INDEX IF NOT EXISTS idx_run_result_scope ON med_index_run_result (hospital_id, rule_id, stat_start_time, stat_end_time);
CREATE INDEX IF NOT EXISTS idx_monitor_plan_status ON med_indicator_run_plan (status);
CREATE INDEX IF NOT EXISTS idx_monitor_plan_next ON med_indicator_run_plan (next_run_at);
CREATE INDEX IF NOT EXISTS idx_monitor_alert_hospital ON med_indicator_alert (hospital_id);
CREATE INDEX IF NOT EXISTS idx_monitor_alert_status ON med_indicator_alert (status);
CREATE INDEX IF NOT EXISTS idx_agent_trace_hospital_started ON med_agent_trace (hospital_id, started_at);
CREATE INDEX IF NOT EXISTS idx_trace_node_trace_id ON med_agent_trace_node (trace_id);
CREATE INDEX IF NOT EXISTS idx_trace_node_status ON med_agent_trace_node (status);
CREATE INDEX IF NOT EXISTS idx_trace_node_rule_id ON med_agent_trace_node (rule_id);
CREATE INDEX IF NOT EXISTS idx_trace_node_subtask ON med_agent_trace_node (trace_id, subtask_id);
CREATE INDEX IF NOT EXISTS idx_trace_node_model ON med_agent_trace_node (model_id);
CREATE INDEX IF NOT EXISTS idx_trace_node_failure_class ON med_agent_trace_node (failure_class);
CREATE INDEX IF NOT EXISTS ix_agent_evidence_trace ON med_agent_evidence (trace_id, subtask_id);
CREATE INDEX IF NOT EXISTS ix_agent_evidence_hospital_created ON med_agent_evidence (hospital_id, created_at);
CREATE INDEX IF NOT EXISTS ix_agent_verification_evidence ON med_agent_evidence_verification (evidence_id, status);
CREATE INDEX IF NOT EXISTS idx_recovery_status ON med_recovery_task (status);
CREATE INDEX IF NOT EXISTS idx_recovery_type ON med_recovery_task (task_type);
CREATE INDEX IF NOT EXISTS idx_recovery_trace ON med_recovery_task (trace_id);
CREATE INDEX IF NOT EXISTS idx_term_alias_text ON med_term_alias (alias_text);
CREATE INDEX IF NOT EXISTS idx_term_rule_code ON med_term_rule_link (index_code);
CREATE INDEX IF NOT EXISTS idx_hospital_term_active ON med_hospital_term_mapping (hospital_id, approval_status);

CREATE TABLE IF NOT EXISTS med_agent_java_message (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  session_key TEXT NOT NULL,
  hospital_id TEXT NOT NULL,
  user_id TEXT NOT NULL,
  role TEXT NOT NULL,
  content TEXT NOT NULL,
  rule_id TEXT,
  rule_name TEXT,
  stat_start TEXT,
  stat_end TEXT,
  run_id TEXT,
  upload_file_key TEXT,
  created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_java_message_session ON med_agent_java_message(session_key, id);
