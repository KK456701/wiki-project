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
('hospital_002', 'P001', '急会诊', '2026-07-01 10:00:00', '2026-07-01 10:15:00', '完成', 'D001'),
('hospital_002', 'P002', '急会诊', '2026-07-01 11:00:00', '2026-07-01 11:35:00', '完成', 'D001'),
('hospital_002', 'P003', '普通会诊', '2026-07-01 12:00:00', '2026-07-01 12:20:00', '完成', 'D002');

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
('hospital_002', 'P001', 'A001', '2026-07-01 08:00:00', '2026-07-02 07:00:00', 'D001', 'D002', '完成'),
('hospital_002', 'P002', 'A002', '2026-07-01 08:00:00', '2026-07-04 09:00:00', 'D001', 'D003', '完成'),
('hospital_002', 'P003', 'A003', '2026-07-01 08:00:00', NULL, 'D001', NULL, '未转科');
