CREATE DATABASE IF NOT EXISTS hospital_demo_data DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE hospital_demo_data;

DROP TABLE IF EXISTS consult_record;
CREATE TABLE consult_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  hospital_id VARCHAR(64) NOT NULL,
  patient_id VARCHAR(64),
  consult_type VARCHAR(32) NOT NULL,
  request_time DATETIME NOT NULL,
  arrive_time DATETIME,
  status VARCHAR(32),
  dept_id VARCHAR(64)
);

INSERT INTO consult_record (hospital_id, patient_id, consult_type, request_time, arrive_time, status, dept_id) VALUES
('hospital_001', 'P001', '急会诊', '2026-07-01 10:00:00', '2026-07-01 10:08:00', '完成', 'D001'),
('hospital_001', 'P002', '急会诊', '2026-07-01 11:00:00', '2026-07-01 11:15:00', '完成', 'D001'),
('hospital_001', 'P003', '急会诊', '2026-07-01 12:00:00', '2026-07-01 12:30:00', '完成', 'D002'),
('hospital_001', 'P004', '普通会诊', '2026-07-01 13:00:00', '2026-07-01 13:10:00', '完成', 'D002');

DROP TABLE IF EXISTS inpatient_transfer_record;
CREATE TABLE inpatient_transfer_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  hospital_id VARCHAR(64) NOT NULL,
  patient_id VARCHAR(64) NOT NULL,
  admission_id VARCHAR(64) NOT NULL,
  admit_time DATETIME NOT NULL,
  transfer_time DATETIME,
  from_dept_id VARCHAR(64),
  to_dept_id VARCHAR(64),
  transfer_status VARCHAR(32)
);

INSERT INTO inpatient_transfer_record (hospital_id, patient_id, admission_id, admit_time, transfer_time, from_dept_id, to_dept_id, transfer_status) VALUES
('hospital_001', 'P011', 'A001', '2026-07-01 08:00:00', '2026-07-02 07:00:00', 'D001', 'D002', '完成'),
('hospital_001', 'P012', 'A002', '2026-07-01 08:00:00', '2026-07-04 09:00:00', 'D001', 'D003', '完成'),
('hospital_001', 'P013', 'A003', '2026-07-01 08:00:00', '2026-07-01 12:00:00', 'ICU', 'D002', '完成'),
('hospital_001', 'P014', 'A004', '2026-07-01 08:00:00', NULL, 'D001', NULL, '未转科');

DROP TABLE IF EXISTS critical_rescue_record;
CREATE TABLE critical_rescue_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  hospital_id VARCHAR(64) NOT NULL,
  patient_id VARCHAR(64) NOT NULL,
  rescue_id VARCHAR(64) NOT NULL,
  rescue_time DATETIME NOT NULL,
  severity_level VARCHAR(32) NOT NULL,
  rescue_result VARCHAR(16) NOT NULL,
  dept_id VARCHAR(64),
  UNIQUE KEY uk_rescue_id (hospital_id, rescue_id),
  CHECK (rescue_result IN ('成功', '失败'))
);

INSERT INTO critical_rescue_record (hospital_id, patient_id, rescue_id, rescue_time, severity_level, rescue_result, dept_id) VALUES
('hospital_001', 'P101', 'R001', '2026-07-02 08:00:00', '急危重症', '成功', 'D001'),
('hospital_001', 'P102', 'R002', '2026-07-02 09:00:00', '急危重症', '成功', 'D001'),
('hospital_001', 'P103', 'R003', '2026-07-02 10:00:00', '急危重症', '成功', 'D002'),
('hospital_001', 'P104', 'R004', '2026-07-02 11:00:00', '急危重症', '失败', 'D002');

DROP TABLE IF EXISTS intraoperative_transfusion_record;
CREATE TABLE intraoperative_transfusion_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  hospital_id VARCHAR(64) NOT NULL,
  patient_id VARCHAR(64) NOT NULL,
  surgery_id VARCHAR(64) NOT NULL,
  surgery_time DATETIME NOT NULL,
  intraoperative_transfusion_flag TINYINT(1) NOT NULL,
  autologous_reinfusion_flag TINYINT(1) NOT NULL,
  dept_id VARCHAR(64),
  CHECK (intraoperative_transfusion_flag IN (0, 1)),
  CHECK (autologous_reinfusion_flag IN (0, 1))
);

INSERT INTO intraoperative_transfusion_record (hospital_id, patient_id, surgery_id, surgery_time, intraoperative_transfusion_flag, autologous_reinfusion_flag, dept_id) VALUES
('hospital_001', 'P201', 'S001', '2026-07-03 08:00:00', 1, 1, 'D001'),
('hospital_001', 'P201', 'S001', '2026-07-03 08:00:00', 1, 1, 'D001'),
('hospital_001', 'P202', 'S002', '2026-07-03 09:00:00', 1, 1, 'D001'),
('hospital_001', 'P203', 'S003', '2026-07-03 10:00:00', 1, 0, 'D002'),
('hospital_001', 'P204', 'S004', '2026-07-03 11:00:00', 1, 0, 'D002');
