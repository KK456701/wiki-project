package com.hospital.wikiagent.agent.diagnosis;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 封装 {@code DiagnosisReportRepository} 对应数据的持久化与查询，避免上层依赖具体存储实现。
 *
 * <p>所有存储语句、JSON 转换和对象有效期检查集中在此处，调用方只传递类型化条件。实现不得绕过医院隔离，也不得把患者级明细写入日志或通用 Trace。</p>
 */
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
