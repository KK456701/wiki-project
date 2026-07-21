DROP ALL OBJECTS;

CREATE TABLE med_hospital_user (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id VARCHAR(64) NOT NULL UNIQUE,
  account_id VARCHAR(64) NOT NULL UNIQUE,
  hospital_id VARCHAR(64) NOT NULL,
  password_hash VARCHAR(128) NOT NULL,
  password_salt VARCHAR(64) NOT NULL,
  password_iterations INT NOT NULL,
  must_change_password TINYINT NOT NULL DEFAULT 1,
  status VARCHAR(32) NOT NULL DEFAULT 'active',
  failed_attempts INT NOT NULL DEFAULT 0,
  locked_until TIMESTAMP NULL,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL
);

CREATE TABLE med_hospital_user_permission (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id VARCHAR(64) NOT NULL,
  permission_code VARCHAR(64) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  UNIQUE (user_id, permission_code)
);

CREATE TABLE med_hospital_session (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id VARCHAR(64) NOT NULL UNIQUE,
  user_id VARCHAR(64) NOT NULL,
  token_hash VARCHAR(64) NOT NULL UNIQUE,
  expires_at TIMESTAMP NOT NULL,
  revoked_at TIMESTAMP NULL,
  created_at TIMESTAMP NOT NULL,
  last_seen_at TIMESTAMP NOT NULL
);

CREATE TABLE med_data_access_audit (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
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
  reason VARCHAR(1000),
  created_at TIMESTAMP NOT NULL
);

CREATE TABLE med_index_standard (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  index_code VARCHAR(64) NOT NULL UNIQUE,
  index_name VARCHAR(128) NOT NULL,
  index_type VARCHAR(32) NOT NULL,
  index_desc VARCHAR(2000) NOT NULL,
  stat_cycle VARCHAR(32) NOT NULL DEFAULT 'month',
  numerator_rule VARCHAR(2000) NOT NULL,
  denominator_rule VARCHAR(2000) NOT NULL,
  filter_rule VARCHAR(2000),
  exclude_rule VARCHAR(2000),
  rely_table_field VARCHAR(8000) NOT NULL,
  calculation_definition VARCHAR(8000),
  standard_sql VARCHAR(8000) NOT NULL,
  rule_params VARCHAR(8000) NOT NULL,
  source_path VARCHAR(512),
  version VARCHAR(64) NOT NULL,
  status TINYINT NOT NULL DEFAULT 1,
  create_time TIMESTAMP NOT NULL,
  update_time TIMESTAMP NOT NULL
);

CREATE TABLE med_index_hospital_custom (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  hospital_id VARCHAR(64) NOT NULL,
  index_code VARCHAR(64) NOT NULL,
  custom_numerator VARCHAR(2000),
  custom_denominator VARCHAR(2000),
  custom_filter VARCHAR(2000),
  exclude_rule VARCHAR(2000),
  custom_params VARCHAR(8000) NOT NULL,
  custom_calculation_patch VARCHAR(8000),
  custom_sql VARCHAR(8000),
  version INT NOT NULL,
  status TINYINT NOT NULL DEFAULT 1,
  approval_status VARCHAR(32) NOT NULL,
  effective_from TIMESTAMP,
  effective_to TIMESTAMP,
  oper_user VARCHAR(64),
  create_time TIMESTAMP NOT NULL,
  update_time TIMESTAMP NOT NULL,
  UNIQUE (hospital_id, index_code)
);

CREATE TABLE med_index_hospital_defined (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  hospital_id VARCHAR(64) NOT NULL,
  index_code VARCHAR(64) NOT NULL,
  index_name VARCHAR(128) NOT NULL,
  index_type VARCHAR(64) NOT NULL,
  index_desc VARCHAR(2000) NOT NULL,
  stat_cycle VARCHAR(32) NOT NULL,
  numerator_rule VARCHAR(2000) NOT NULL,
  denominator_rule VARCHAR(2000) NOT NULL,
  filter_rule VARCHAR(2000),
  exclude_rule VARCHAR(2000),
  field_contract VARCHAR(8000) NOT NULL,
  calculation_definition VARCHAR(8000),
  sql_template VARCHAR(8000) NOT NULL,
  rule_params VARCHAR(8000) NOT NULL,
  version INT NOT NULL,
  status TINYINT NOT NULL DEFAULT 1,
  approval_status VARCHAR(32) NOT NULL,
  effective_from TIMESTAMP,
  effective_to TIMESTAMP,
  source_draft_id VARCHAR(64) NOT NULL,
  oper_user VARCHAR(64) NOT NULL,
  create_time TIMESTAMP NOT NULL,
  update_time TIMESTAMP NOT NULL,
  UNIQUE (hospital_id, index_code)
);

CREATE TABLE med_field_mapping (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  hospital_id VARCHAR(64) NOT NULL,
  rule_id VARCHAR(64) NOT NULL,
  business_field VARCHAR(128) NOT NULL,
  db_name VARCHAR(128) NOT NULL,
  table_name VARCHAR(128) NOT NULL,
  column_name VARCHAR(128) NOT NULL,
  data_type VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL
);

CREATE TABLE med_metadata_table (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  hospital_id VARCHAR(64) NOT NULL,
  db_name VARCHAR(128) NOT NULL,
  table_name VARCHAR(128) NOT NULL,
  table_comment CLOB,
  table_type VARCHAR(32),
  sync_batch_id VARCHAR(64) NOT NULL,
  sync_time TIMESTAMP NOT NULL,
  UNIQUE (hospital_id, db_name, table_name)
);

CREATE TABLE med_metadata_column (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  hospital_id VARCHAR(64) NOT NULL,
  db_name VARCHAR(128) NOT NULL,
  table_name VARCHAR(128) NOT NULL,
  column_name VARCHAR(128) NOT NULL,
  data_type VARCHAR(64),
  column_type VARCHAR(128),
  is_nullable VARCHAR(8),
  column_key VARCHAR(32),
  column_default CLOB,
  column_comment CLOB,
  sync_batch_id VARCHAR(64) NOT NULL,
  sync_time TIMESTAMP NOT NULL,
  UNIQUE (hospital_id, db_name, table_name, column_name)
);

CREATE TABLE med_metadata_sync_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  hospital_id VARCHAR(64) NOT NULL,
  db_name VARCHAR(128) NOT NULL,
  table_name VARCHAR(128),
  field_name VARCHAR(128),
  change_type VARCHAR(32),
  change_desc CLOB,
  sync_batch_id VARCHAR(64) NOT NULL,
  sync_time TIMESTAMP NOT NULL
);

CREATE TABLE med_metadata_snapshot (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  hospital_id VARCHAR(64) NOT NULL,
  db_name VARCHAR(128) NOT NULL,
  metadata_source VARCHAR(32) NOT NULL,
  sync_batch_id VARCHAR(64) NOT NULL,
  snapshot_json CLOB NOT NULL,
  created_at TIMESTAMP NOT NULL
);

CREATE TABLE med_table_relation (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  hospital_id VARCHAR(64) NOT NULL,
  db_name VARCHAR(128) NOT NULL,
  left_table VARCHAR(128) NOT NULL,
  left_column VARCHAR(128) NOT NULL,
  right_table VARCHAR(128) NOT NULL,
  right_column VARCHAR(128) NOT NULL,
  join_type VARCHAR(32) NOT NULL,
  relation_source VARCHAR(64),
  status VARCHAR(32) NOT NULL
);

CREATE TABLE med_generated_sql (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  sql_id VARCHAR(80) NOT NULL UNIQUE,
  hospital_id VARCHAR(128) NOT NULL,
  rule_id VARCHAR(128) NOT NULL,
  dialect VARCHAR(32) NOT NULL,
  sql_text CLOB NOT NULL,
  sql_status VARCHAR(32) NOT NULL,
  validation_message CLOB,
  generated_by VARCHAR(128),
  generated_at TIMESTAMP NOT NULL
);

CREATE TABLE med_agent_sql_object (
  sql_id VARCHAR(80) PRIMARY KEY,
  hospital_id VARCHAR(128) NOT NULL,
  user_id VARCHAR(128) NOT NULL,
  session_id VARCHAR(128) NOT NULL,
  rule_id VARCHAR(128) NOT NULL,
  dialect VARCHAR(32) NOT NULL,
  sql_text CLOB NOT NULL,
  params_json CLOB NOT NULL,
  stat_start VARCHAR(32) NOT NULL,
  stat_end VARCHAR(32) NOT NULL,
  context_snapshot_json CLOB NOT NULL,
  context_digest VARCHAR(64) NOT NULL,
  validation_status VARCHAR(32) NOT NULL,
  validation_message CLOB NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  expires_at VARCHAR(40) NOT NULL,
  db_source_id VARCHAR(128)
);

CREATE TABLE med_sql_run_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  run_id VARCHAR(64) NOT NULL UNIQUE,
  sql_id VARCHAR(80),
  hospital_id VARCHAR(128) NOT NULL,
  rule_id VARCHAR(128) NOT NULL,
  stat_start_time TIMESTAMP,
  stat_end_time TIMESTAMP,
  run_status VARCHAR(32) NOT NULL,
  result_value DECIMAL(18,4),
  error_message CLOB,
  duration_ms INT,
  run_by VARCHAR(128),
  numerator_count BIGINT,
  denominator_count BIGINT,
  run_context_json CLOB,
  run_time TIMESTAMP NOT NULL
);

CREATE TABLE med_index_diagnose_report (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  report_id VARCHAR(64) NOT NULL UNIQUE,
  hospital_id VARCHAR(64) NOT NULL,
  rule_id VARCHAR(64) NOT NULL,
  diagnose_type VARCHAR(32) NOT NULL,
  problem_detail CLOB,
  repair_suggest CLOB,
  repair_sql CLOB,
  diagnose_time TIMESTAMP NOT NULL,
  status TINYINT NOT NULL,
  trigger_type VARCHAR(64) NOT NULL,
  related_sql_id VARCHAR(64),
  layer_results CLOB,
  diagnose_status VARCHAR(32) NOT NULL,
  stat_period VARCHAR(128)
);
