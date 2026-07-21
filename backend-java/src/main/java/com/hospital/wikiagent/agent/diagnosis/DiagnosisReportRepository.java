package com.hospital.wikiagent.agent.diagnosis;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.ObjectMapper;

@Repository
public class DiagnosisReportRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public DiagnosisReportRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void save(
            String reportId,
            String hospitalId,
            String ruleId,
            String diagnoseType,
            String problemDetail,
            String repairSuggest,
            List<Map<String, Object>> layers,
            String diagnoseStatus,
            String statPeriod) {
        jdbc.update(
                "INSERT INTO med_index_diagnose_report "
                        + "(report_id,hospital_id,rule_id,diagnose_type,problem_detail,repair_suggest,repair_sql,"
                        + "diagnose_time,status,trigger_type,related_sql_id,layer_results,diagnose_status,stat_period) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                reportId, hospitalId, ruleId, diagnoseType, problemDetail, repairSuggest, "",
                Timestamp.from(Instant.now()), "failed".equals(diagnoseStatus) ? 0 : 1,
                "agent_tool", null, json(layers), diagnoseStatus, statPeriod);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("诊断报告序列化失败", exception);
        }
    }
}
