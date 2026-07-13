CREATE DATABASE IF NOT EXISTS wiki_company_kb DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE wiki_company_kb;

CREATE TABLE IF NOT EXISTS company_standard_rule (
  rule_id VARCHAR(64) PRIMARY KEY,
  rule_name VARCHAR(128) NOT NULL,
  definition TEXT NOT NULL,
  formula TEXT NOT NULL,
  payload_json JSON NOT NULL,
  version INT NOT NULL,
  status VARCHAR(32) NOT NULL,
  updated_at DATETIME NOT NULL,
  KEY idx_company_standard_status (status)
);

CREATE TABLE IF NOT EXISTS company_standard_rule_version (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  rule_id VARCHAR(64) NOT NULL,
  version INT NOT NULL,
  payload_json JSON NOT NULL,
  source_release_id VARCHAR(64),
  created_at DATETIME NOT NULL,
  UNIQUE KEY uk_company_rule_version (rule_id, version)
);

CREATE TABLE IF NOT EXISTS company_kb_package (
  package_id VARCHAR(64) PRIMARY KEY,
  report_id VARCHAR(64) NOT NULL UNIQUE,
  hospital_id VARCHAR(64) NOT NULL,
  format_version VARCHAR(32) NOT NULL,
  exported_at DATETIME,
  uploaded_at DATETIME NOT NULL,
  uploaded_by VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,
  manifest_json JSON NOT NULL,
  package_checksum CHAR(64) NOT NULL,
  KEY idx_company_package_hospital (hospital_id, uploaded_at),
  KEY idx_company_package_status (status)
);

CREATE TABLE IF NOT EXISTS company_kb_package_item (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  package_id VARCHAR(64) NOT NULL,
  item_id VARCHAR(64) NOT NULL,
  item_type VARCHAR(32) NOT NULL,
  rule_id VARCHAR(64),
  rule_name VARCHAR(128),
  field_name VARCHAR(128),
  hospital_value_json JSON,
  company_value_json JSON,
  source_payload_json JSON NOT NULL,
  status VARCHAR(32) NOT NULL,
  decision VARCHAR(64),
  approver_id VARCHAR(64),
  decision_reason TEXT,
  decided_at DATETIME,
  UNIQUE KEY uk_company_package_item (package_id, item_id),
  KEY idx_company_item_status (package_id, status),
  KEY idx_company_item_rule (rule_id)
);

CREATE TABLE IF NOT EXISTS company_rule_candidate (
  candidate_id VARCHAR(64) PRIMARY KEY,
  package_id VARCHAR(64) NOT NULL,
  item_id VARCHAR(64) NOT NULL,
  source_hospital_id VARCHAR(64) NOT NULL,
  rule_id VARCHAR(64) NOT NULL,
  payload_json JSON NOT NULL,
  status VARCHAR(32) NOT NULL,
  created_at DATETIME NOT NULL,
  created_by VARCHAR(64) NOT NULL,
  release_id VARCHAR(64),
  UNIQUE KEY uk_company_candidate_source (package_id, item_id),
  KEY idx_company_candidate_status (status)
);

CREATE TABLE IF NOT EXISTS company_term_candidate (
  candidate_id VARCHAR(64) PRIMARY KEY,
  package_id VARCHAR(64) NOT NULL,
  item_id VARCHAR(64) NOT NULL,
  source_hospital_id VARCHAR(64) NOT NULL,
  concept_code VARCHAR(96) NOT NULL,
  candidate_type VARCHAR(32) NOT NULL,
  payload_json JSON NOT NULL,
  status VARCHAR(32) NOT NULL,
  created_at DATETIME NOT NULL,
  created_by VARCHAR(64) NOT NULL,
  UNIQUE KEY uk_company_term_candidate_source (package_id, item_id),
  KEY idx_company_term_candidate_status (status),
  KEY idx_company_term_candidate_concept (concept_code)
);

CREATE TABLE IF NOT EXISTS company_release (
  release_id VARCHAR(64) PRIMARY KEY,
  version INT NOT NULL UNIQUE,
  status VARCHAR(32) NOT NULL,
  notes TEXT,
  created_by VARCHAR(64) NOT NULL,
  approved_by VARCHAR(64),
  created_at DATETIME NOT NULL,
  published_at DATETIME,
  KEY idx_company_release_status (status)
);

CREATE TABLE IF NOT EXISTS company_release_item (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  release_id VARCHAR(64) NOT NULL,
  candidate_id VARCHAR(64) NOT NULL,
  rule_id VARCHAR(64) NOT NULL,
  payload_json JSON NOT NULL,
  UNIQUE KEY uk_company_release_candidate (release_id, candidate_id),
  KEY idx_company_release_rule (release_id, rule_id)
);
