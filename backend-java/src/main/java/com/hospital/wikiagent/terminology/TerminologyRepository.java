package com.hospital.wikiagent.terminology;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 封装 {@code TerminologyRepository} 对应数据的持久化与查询，避免上层依赖具体存储实现。
 *
 * <p>所有存储语句、JSON 转换和对象有效期检查集中在此处，调用方只传递类型化条件。实现不得绕过医院隔离，也不得把患者级明细写入日志或通用 Trace。</p>
 */
@Repository
public class TerminologyRepository {
    private final JdbcTemplate jdbc;

    public TerminologyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> concepts() {
        return rows("SELECT * FROM med_term_concept WHERE status='active' ORDER BY concept_code");
    }

    public Map<String, Object> concept(String conceptCode) {
        List<Map<String, Object>> values = rows(
                "SELECT * FROM med_term_concept WHERE concept_code=?", conceptCode);
        return values.isEmpty() ? Map.of() : values.get(0);
    }

    public List<Map<String, Object>> aliases(String approvalStatus) {
        return rows("SELECT * FROM med_term_alias WHERE hospital_id='' AND approval_status=? "
                + "ORDER BY concept_code,alias_text", approvalStatus);
    }

    public List<Map<String, Object>> conceptAliases(String conceptCode, String hospitalId) {
        return rows("SELECT * FROM med_term_alias WHERE concept_code=? "
                + "AND hospital_id IN ('',?) ORDER BY approval_status,alias_text",
                conceptCode, hospitalId);
    }

    public List<Map<String, Object>> hospitalAliases(String hospitalId) {
        return rows("SELECT * FROM med_term_alias WHERE hospital_id=? AND approval_status='approved' "
                + "ORDER BY concept_code,alias_text", hospitalId);
    }

    public List<Map<String, Object>> ruleLinks() {
        return rows("SELECT * FROM med_term_rule_link ORDER BY index_code,concept_code");
    }

    public List<Map<String, Object>> conceptRuleLinks(String conceptCode) {
        return rows("SELECT * FROM med_term_rule_link WHERE concept_code=? "
                + "ORDER BY index_code,usage_section", conceptCode);
    }

    public List<Map<String, Object>> hospitalMappings(String hospitalId, String conceptCode) {
        return rows("SELECT * FROM med_hospital_term_mapping WHERE hospital_id=? AND concept_code=? "
                + "ORDER BY version DESC", hospitalId, conceptCode);
    }

    public List<Map<String, Object>> activeHospitalMappings(String hospitalId) {
        LocalDateTime now = LocalDateTime.now();
        return rows("SELECT * FROM med_hospital_term_mapping WHERE hospital_id=? "
                + "AND approval_status='approved' AND (effective_from IS NULL OR effective_from<=?) "
                + "AND (effective_to IS NULL OR effective_to>?) ORDER BY concept_code,version DESC",
                hospitalId, now, now);
    }

    public Map<String, Object> activeRelease() {
        List<Map<String, Object>> values = rows(
                "SELECT release_id,version,status,checksum,change_summary,published_by,published_at "
                        + "FROM med_term_release WHERE status='active' ORDER BY version DESC LIMIT 1");
        return values.isEmpty() ? Map.of() : values.get(0);
    }

    public List<Map<String, Object>> releases() {
        return rows("SELECT release_id,version,status,checksum,change_summary,published_by,published_at "
                + "FROM med_term_release ORDER BY version DESC");
    }

    private List<Map<String, Object>> rows(String sql, Object... arguments) {
        List<Map<String, Object>> values = jdbc.queryForList(sql, arguments);
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Map<String, Object> value : values) {
            Map<String, Object> item = new LinkedHashMap<>();
            value.forEach((key, field) -> item.put(key.toLowerCase(Locale.ROOT), field));
            normalized.add(item);
        }
        return List.copyOf(normalized);
    }
}
