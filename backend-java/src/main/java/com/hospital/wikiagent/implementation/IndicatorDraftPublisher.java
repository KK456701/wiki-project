package com.hospital.wikiagent.implementation;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class IndicatorDraftPublisher {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final IndicatorDraftRepository drafts;

    public IndicatorDraftPublisher(
            JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper json,
            IndicatorDraftRepository drafts) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.json = json;
        this.drafts = drafts;
    }

    public Map<String, Object> approve(
            String draftId, String hospitalId, int expectedVersion, String approverId) {
        return transactions.execute(status -> {
            Map<String, Object> draft = requirePending(draftId, hospitalId, expectedVersion);
            String baseCode = text(draft.get("base_index_code"));
            Publication publication = baseCode.isBlank()
                    ? publishDefined(draft, approverId) : publishCaliber(draft, approverId);
            replaceMappings(hospitalId, publication.formalCode(), map(draft.get("field_mapping")), approverId);
            drafts.workflowTransition(draftId, hospitalId, expectedVersion,
                    "pending_approval", "published", Map.of("formal_index_code", publication.formalCode()),
                    approverId, "published");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("draft_id", draftId);
            result.put("status", "published");
            result.put("publication_type", publication.type());
            result.put("formal_index_code", publication.formalCode());
            result.put("active_version", publication.version());
            result.put("approver_id", approverId);
            return result;
        });
    }

    public Map<String, Object> reject(
            String draftId, String hospitalId, int expectedVersion, String approverId, String reason) {
        requirePending(draftId, hospitalId, expectedVersion);
        String cleanReason = text(reason);
        if (cleanReason.isBlank()) throw new ImplementationException(
                "DRAFT_REJECT_REASON_REQUIRED", "驳回时必须填写原因。", 400);
        return drafts.workflowTransition(draftId, hospitalId, expectedVersion,
                "pending_approval", "rejected", Map.of(), approverId,
                ("rejected:" + cleanReason).substring(0, Math.min(64, 9 + cleanReason.length())));
    }

    public Map<String, Object> listVersions(String hospitalId, String indexCode) {
        List<Integer> activeRows = jdbc.query(
                "SELECT version FROM med_index_hospital_defined WHERE hospital_id=? AND index_code=?",
                (result, ignored) -> result.getInt(1), hospitalId, indexCode);
        if (activeRows.isEmpty()) throw new ImplementationException(
                "HOSPITAL_DEFINED_RULE_NOT_FOUND", "本院自定义指标不存在。", 404);
        int active = activeRows.get(0);
        List<Map<String, Object>> versions = jdbc.query("""
                SELECT version,snapshot_json,source_version,change_type,oper_user,approver_id,approved_at
                FROM med_index_hospital_defined_version
                WHERE hospital_id=? AND index_code=? ORDER BY version DESC
                """, (result, ignored) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            int version = result.getInt("version");
            item.put("version", version);
            item.put("snapshot", readMap(result.getString("snapshot_json")));
            item.put("source_version", result.getObject("source_version"));
            item.put("change_type", result.getString("change_type"));
            item.put("oper_user", result.getString("oper_user"));
            item.put("approver_id", result.getString("approver_id"));
            item.put("approved_at", result.getObject("approved_at"));
            item.put("active", version == active);
            return item;
        }, hospitalId, indexCode);
        return Map.of("hospital_id", hospitalId, "index_code", indexCode,
                "active_version", active, "versions", versions);
    }

    public Map<String, Object> restore(
            String hospitalId, String indexCode, int version, String approverId) {
        return transactions.execute(status -> {
            List<String> rows = jdbc.query("""
                    SELECT snapshot_json FROM med_index_hospital_defined_version
                    WHERE hospital_id=? AND index_code=? AND version=?
                    """, (result, ignored) -> result.getString(1), hospitalId, indexCode, version);
            if (rows.isEmpty()) throw new ImplementationException(
                    "HOSPITAL_DEFINED_VERSION_NOT_FOUND", "待恢复的本院指标版本不存在。", 404);
            Map<String, Object> snapshot = new LinkedHashMap<>(readMap(rows.get(0)));
            int nextVersion = nextDefinedVersion(hospitalId, indexCode);
            snapshot.put("version", nextVersion);
            writeDefinedCurrent(snapshot, approverId);
            insertDefinedVersion(snapshot, version, null, "restore", approverId, approverId);
            replaceMappings(hospitalId, indexCode, map(snapshot.get("field_mapping")), approverId);
            return Map.of("hospital_id", hospitalId, "index_code", indexCode,
                    "active_version", nextVersion, "restored_from_version", version,
                    "approver_id", approverId);
        });
    }

    private Publication publishDefined(Map<String, Object> draft, String approverId) {
        String hospitalId = text(draft.get("hospital_id"));
        String indexCode = text(draft.get("proposed_index_code"));
        int version = nextDefinedVersion(hospitalId, indexCode);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        copy(draft, snapshot, "hospital_id", "index_name", "index_type", "index_desc", "stat_cycle",
                "numerator_rule", "denominator_rule", "filter_rule", "exclude_rule");
        snapshot.put("index_code", indexCode);
        snapshot.put("field_contract", draft.get("metadata_requirements"));
        snapshot.put("field_mapping", draft.get("field_mapping"));
        snapshot.put("sql_template", draft.get("current_sql"));
        snapshot.put("rule_params", draft.get("sql_params"));
        snapshot.put("version", version);
        snapshot.put("status", 1);
        snapshot.put("approval_status", "approved");
        snapshot.put("effective_from", null);
        snapshot.put("effective_to", null);
        snapshot.put("source_draft_id", draft.get("draft_id"));
        writeDefinedCurrent(snapshot, approverId);
        insertDefinedVersion(snapshot, null, text(draft.get("draft_id")),
                "draft_publish", text(draft.get("updated_by")), approverId);
        return new Publication("hospital_defined", indexCode, version);
    }

    private Publication publishCaliber(Map<String, Object> draft, String approverId) {
        String hospitalId = text(draft.get("hospital_id"));
        String indexCode = text(draft.get("base_index_code"));
        Integer standards = jdbc.queryForObject(
                "SELECT COUNT(*) FROM med_index_standard WHERE index_code=? AND status=1",
                Integer.class, indexCode);
        if (standards == null || standards == 0) throw new ImplementationException(
                "BASE_STANDARD_RULE_NOT_FOUND", "待覆盖的国标指标不存在或未启用。", 409);
        Integer max = jdbc.queryForObject("""
                SELECT MAX(version) FROM med_index_hospital_custom_version
                WHERE hospital_id=? AND index_code=?
                """, Integer.class, hospitalId, indexCode);
        int version = (max == null ? 0 : max) + 1;
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("custom_numerator", draft.get("numerator_rule"));
        snapshot.put("custom_denominator", draft.get("denominator_rule"));
        snapshot.put("custom_filter", draft.get("filter_rule"));
        snapshot.put("exclude_rule", draft.get("exclude_rule"));
        snapshot.put("custom_params", draft.get("sql_params"));
        snapshot.put("custom_sql", draft.get("current_sql"));
        snapshot.put("status", 1);
        snapshot.put("effective_from", null);
        snapshot.put("effective_to", null);
        snapshot.put("field_mapping", draft.get("field_mapping"));
        LocalDateTime now = now();
        jdbc.update("""
                INSERT INTO med_index_hospital_custom_version
                  (change_id,hospital_id,index_code,version,approval_status,snapshot_json,source_version,
                   change_type,oper_user,approver_id,created_at,approved_at)
                VALUES (?,?,?,?,'approved',?,NULL,'draft_publish',?,?,?,?)
                """, "DRAFTPUB_" + shortId(), hospitalId, indexCode, version, write(snapshot),
                text(draft.get("updated_by")), approverId, now, now);
        int exists = count("SELECT COUNT(*) FROM med_index_hospital_custom WHERE hospital_id=? AND index_code=?",
                hospitalId, indexCode);
        if (exists == 0) {
            jdbc.update("""
                    INSERT INTO med_index_hospital_custom
                      (hospital_id,index_code,custom_numerator,custom_denominator,custom_filter,exclude_rule,
                       custom_params,custom_sql,version,status,approval_status,effective_from,effective_to,
                       oper_user,create_time,update_time)
                    VALUES (?,?,?,?,?,?,?, ?,?,1,'approved',NULL,NULL,?,?,?)
                    """, hospitalId, indexCode, draft.get("numerator_rule"), draft.get("denominator_rule"),
                    draft.get("filter_rule"), draft.get("exclude_rule"), write(draft.get("sql_params")),
                    draft.get("current_sql"), version, approverId, now, now);
        } else {
            jdbc.update("""
                    UPDATE med_index_hospital_custom SET custom_numerator=?,custom_denominator=?,custom_filter=?,
                      exclude_rule=?,custom_params=?,custom_sql=?,version=?,status=1,approval_status='approved',
                      oper_user=?,update_time=? WHERE hospital_id=? AND index_code=?
                    """, draft.get("numerator_rule"), draft.get("denominator_rule"), draft.get("filter_rule"),
                    draft.get("exclude_rule"), write(draft.get("sql_params")), draft.get("current_sql"), version,
                    approverId, now, hospitalId, indexCode);
        }
        return new Publication("hospital_caliber", indexCode, version);
    }

    private void writeDefinedCurrent(Map<String, Object> snapshot, String approverId) {
        String hospitalId = text(snapshot.get("hospital_id"));
        String indexCode = text(snapshot.get("index_code"));
        LocalDateTime now = now();
        Object[] values = {
                snapshot.get("index_name"), snapshot.get("index_type"), snapshot.get("index_desc"),
                snapshot.get("stat_cycle"), snapshot.get("numerator_rule"), snapshot.get("denominator_rule"),
                snapshot.get("filter_rule"), snapshot.get("exclude_rule"), write(snapshot.get("field_contract")),
                snapshot.get("sql_template"), write(snapshot.get("rule_params")), snapshot.get("version"),
                snapshot.get("source_draft_id"), approverId, now
        };
        int exists = count("SELECT COUNT(*) FROM med_index_hospital_defined WHERE hospital_id=? AND index_code=?",
                hospitalId, indexCode);
        if (exists == 0) {
            jdbc.update("""
                    INSERT INTO med_index_hospital_defined
                      (hospital_id,index_code,index_name,index_type,index_desc,stat_cycle,numerator_rule,
                       denominator_rule,filter_rule,exclude_rule,field_contract,sql_template,rule_params,
                       version,status,approval_status,effective_from,effective_to,source_draft_id,oper_user,
                       create_time,update_time)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,1,'approved',NULL,NULL,?,?,?,?)
                    """, hospitalId, indexCode, values[0], values[1], values[2], values[3], values[4], values[5],
                    values[6], values[7], values[8], values[9], values[10], values[11], values[12], values[13], now, now);
        } else {
            jdbc.update("""
                    UPDATE med_index_hospital_defined SET index_name=?,index_type=?,index_desc=?,stat_cycle=?,
                      numerator_rule=?,denominator_rule=?,filter_rule=?,exclude_rule=?,field_contract=?,
                      sql_template=?,rule_params=?,version=?,status=1,approval_status='approved',
                      effective_from=NULL,effective_to=NULL,source_draft_id=?,oper_user=?,update_time=?
                    WHERE hospital_id=? AND index_code=?
                    """, values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7],
                    values[8], values[9], values[10], values[11], values[12], values[13], values[14], hospitalId, indexCode);
        }
    }

    private void insertDefinedVersion(
            Map<String, Object> snapshot, Integer sourceVersion, String sourceDraftId,
            String changeType, String operUser, String approverId) {
        LocalDateTime now = now();
        jdbc.update("""
                INSERT INTO med_index_hospital_defined_version
                  (hospital_id,index_code,version,snapshot_json,source_version,source_draft_id,change_type,
                   oper_user,approver_id,created_at,approved_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, snapshot.get("hospital_id"), snapshot.get("index_code"), snapshot.get("version"),
                write(snapshot), sourceVersion, sourceDraftId, changeType, operUser, approverId, now, now);
    }

    private void replaceMappings(
            String hospitalId, String ruleId, Map<String, Object> mappings, String actorId) {
        jdbc.update("DELETE FROM med_field_mapping WHERE hospital_id=? AND rule_id=?", hospitalId, ruleId);
        LocalDateTime now = now();
        mappings.forEach((businessField, raw) -> {
            Map<String, Object> item = map(raw);
            jdbc.update("""
                    INSERT INTO med_field_mapping
                      (hospital_id,rule_id,business_field,db_name,table_name,column_name,data_type,status,
                       updated_by,updated_at)
                    VALUES (?,?,?,?,?,?,?,'confirmed',?,?)
                    """, hospitalId, ruleId, businessField, text(item.get("db_name")),
                    text(item.get("table_name")), text(item.get("column_name")),
                    text(item.get("data_type")), actorId, now);
        });
    }

    private Map<String, Object> requirePending(String draftId, String hospitalId, int expectedVersion) {
        Map<String, Object> draft = drafts.requireDraft(draftId, hospitalId);
        if (integer(draft.get("current_version")) != expectedVersion) throw new ImplementationException(
                "DRAFT_VERSION_CONFLICT", "设计稿版本已变化，请刷新后重试。", 409);
        Map<String, Object> trial = map(draft.get("trial_result"));
        if (!"pending_approval".equals(draft.get("status"))) throw new ImplementationException(
                "DRAFT_NOT_PENDING_APPROVAL", "只有待审批的实施任务可以批准或驳回。", 409);
        if (!"success".equals(trial.get("status"))
                || integer(draft.get("trial_draft_version")) != expectedVersion) {
            throw new ImplementationException("DRAFT_TRIAL_EVIDENCE_STALE",
                    "当前版本缺少有效 SQL 试运行证据。", 409);
        }
        return draft;
    }

    private int nextDefinedVersion(String hospitalId, String indexCode) {
        Integer current = jdbc.query("""
                SELECT version FROM med_index_hospital_defined WHERE hospital_id=? AND index_code=?
                """, result -> result.next() ? result.getInt(1) : null, hospitalId, indexCode);
        return (current == null ? 0 : current) + 1;
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value == null ? Map.of() : value); }
        catch (RuntimeException exception) { throw new ImplementationException(
                "DRAFT_JSON_INVALID", "实施数据无法序列化。", 500); }
    }

    private Map<String, Object> readMap(String value) {
        try { return new LinkedHashMap<>(json.readValue(value, new TypeReference<Map<String, Object>>() { })); }
        catch (RuntimeException exception) { throw new ImplementationException(
                "DRAFT_VERSION_CORRUPTED", "本院指标版本快照损坏。", 500); }
    }

    private static void copy(Map<String, Object> source, Map<String, Object> target, String... keys) {
        for (String key : keys) target.put(key, source.get(key));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> raw ? (Map<String, Object>) raw : Map.of();
    }

    private static int integer(Object value) {
        return value instanceof Number number ? number.intValue()
                : value == null || value.toString().isBlank() ? 0 : Integer.parseInt(value.toString());
    }

    private static String text(Object value) { return value == null ? "" : value.toString().strip(); }
    private static LocalDateTime now() { return LocalDateTime.now().withNano(0); }
    private static String shortId() { return UUID.randomUUID().toString().replace("-", "").substring(0, 12); }
    private record Publication(String type, String formalCode, int version) { }
}
