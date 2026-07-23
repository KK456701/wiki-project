package com.hospital.wikiagent.agent.diagnosis;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
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

    /**
     * 保存差异诊断的安全报告对象。报告只包含汇总、对象引用和版本信息，患者级行仍留在
     * 现有短期明细快照中，不能写入本表。
     */
    public void saveDifference(
            String reportId,
            String hospitalId,
            String ruleId,
            String problemDetail,
            String repairSuggest,
            Map<String, Object> safeReport,
            String diagnoseStatus,
            String statPeriod,
            String relatedSqlId) {
        jdbc.update(
                "INSERT INTO med_index_diagnose_report "
                        + "(report_id,hospital_id,rule_id,diagnose_type,problem_detail,repair_suggest,repair_sql,"
                        + "diagnose_time,status,trigger_type,related_sql_id,layer_results,diagnose_status,stat_period) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                reportId, hospitalId, ruleId, "结果差异分层诊断", problemDetail, repairSuggest, "",
                Timestamp.from(Instant.now()), "blocked".equals(diagnoseStatus) ? 0 : 1,
                "agent_tool", relatedSqlId, json(safeReport), diagnoseStatus, statPeriod);
    }

    /**
     * 按医院作用域加载报告，避免根据可枚举 report_id 跨医院读取导出对象。
     */
    public Optional<StoredReport> find(String reportId, String hospitalId) {
        try {
            return jdbc.query(
                    "SELECT report_id,hospital_id,rule_id,diagnose_type,layer_results,"
                            + "diagnose_status,stat_period,diagnose_time "
                            + "FROM med_index_diagnose_report WHERE report_id=? AND hospital_id=?",
                    (result, row) -> new StoredReport(
                            result.getString("report_id"),
                            result.getString("hospital_id"),
                            result.getString("rule_id"),
                            result.getString("diagnose_type"),
                            object(result.getString("layer_results")),
                            result.getString("diagnose_status"),
                            result.getString("stat_period"),
                            result.getTimestamp("diagnose_time").toInstant()),
                    reportId, hospitalId).stream().findFirst();
        } catch (DataAccessException exception) {
            return Optional.empty();
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("诊断报告序列化失败", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> object(String value) {
        try {
            Object parsed = objectMapper.readValue(value == null ? "{}" : value, Object.class);
            if (!(parsed instanceof Map<?, ?> source)) return Map.of();
            return objectMapper.convertValue(source, Map.class);
        } catch (Exception exception) {
            return Map.of();
        }
    }

    public record StoredReport(
            String reportId,
            String hospitalId,
            String ruleId,
            String diagnoseType,
            Map<String, Object> payload,
            String diagnoseStatus,
            String statPeriod,
            Instant createdAt) {
        public StoredReport {
            payload = payload == null ? Map.of() : Map.copyOf(payload);
        }
    }
}
