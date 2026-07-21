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
