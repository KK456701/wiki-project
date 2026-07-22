package com.hospital.wikiagent.implementation;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public class IndicatorDraftRepository {
    private static final Set<String> EDITABLE = Set.of(
            "base_index_code", "proposed_index_code", "index_name", "index_type", "index_desc",
            "stat_cycle", "numerator_rule", "denominator_rule", "filter_rule", "exclude_rule",
            "metric_type", "metadata_requirements");
    private static final Set<String> JSON_FIELDS = Set.of(
            "metadata_requirements", "field_mapping", "sql_plan", "sql_params", "trial_result");
    private static final Set<String> WORKFLOW_FIELDS = Set.of(
            "field_mapping", "current_sql", "sql_params", "sql_id", "trial_result",
            "trial_draft_version", "formal_index_code");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final RowMapper<Map<String, Object>> mapper = this::mapRow;

    public IndicatorDraftRepository(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper json) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.json = json;
    }

    public List<Map<String, Object>> list(String hospitalId, String status) {
        if (status == null || status.isBlank()) {
            return jdbc.query("""
                    SELECT * FROM med_indicator_draft WHERE hospital_id=?
                    ORDER BY updated_at DESC,draft_id DESC
                    """, mapper, hospitalId);
        }
        return jdbc.query("""
                SELECT * FROM med_indicator_draft WHERE hospital_id=? AND status=?
                ORDER BY updated_at DESC,draft_id DESC
                """, mapper, hospitalId, status.strip());
    }

    public Optional<Map<String, Object>> get(String draftId, String hospitalId) {
        return jdbc.query("SELECT * FROM med_indicator_draft WHERE draft_id=? AND hospital_id=?",
                mapper, draftId, hospitalId).stream().findFirst();
    }

    public Map<String, Object> update(
            String draftId, String hospitalId, int expectedVersion,
            Map<String, Object> changes, String actorId) {
        if (changes == null || changes.isEmpty()) {
            throw new ImplementationException("DRAFT_CHANGES_REQUIRED", "请至少修改一个设计字段。", 400);
        }
        Set<String> unknown = new java.util.HashSet<>(changes.keySet());
        unknown.removeAll(EDITABLE);
        if (!unknown.isEmpty()) {
            throw new ImplementationException("DRAFT_FIELD_NOT_EDITABLE", "包含不可编辑的设计字段。", 400);
        }
        return transactions.execute(status -> {
            Map<String, Object> current = require(draftId, hospitalId);
            requireVersion(current, expectedVersion);
            int nextVersion = expectedVersion + 1;
            List<Object> args = new ArrayList<>();
            StringBuilder assignments = new StringBuilder();
            for (Map.Entry<String, Object> entry : changes.entrySet()) {
                if (assignments.length() > 0) assignments.append(',');
                assignments.append(entry.getKey()).append("=?");
                args.add(JSON_FIELDS.contains(entry.getKey()) ? write(entry.getValue()) : entry.getValue());
            }
            assignments.append(",status='requirements_pending',current_version=?,updated_by=?,updated_at=?,")
                    .append("current_sql=NULL,sql_params='{}',sql_id=NULL,trial_result='{}',trial_draft_version=NULL");
            LocalDateTime now = now();
            args.add(nextVersion); args.add(actorId); args.add(now);
            args.add(draftId); args.add(hospitalId); args.add(expectedVersion);
            int changed = jdbc.update("UPDATE med_indicator_draft SET " + assignments
                    + " WHERE draft_id=? AND hospital_id=? AND current_version=?", args.toArray());
            if (changed != 1) throw conflict();
            Map<String, Object> saved = require(draftId, hospitalId);
            snapshot(saved, "edited", actorId, now);
            return saved;
        });
    }

    public Map<String, Object> confirmRequirements(
            String draftId, String hospitalId, int expectedVersion, String actorId) {
        return transition(draftId, hospitalId, expectedVersion, "requirements_pending",
                "metadata_pending", actorId, "requirements_confirmed", false);
    }

    public Map<String, Object> submit(
            String draftId, String hospitalId, int expectedVersion, String actorId) {
        Map<String, Object> current = require(draftId, hospitalId);
        requireVersion(current, expectedVersion);
        Map<String, Object> trial = map(current.get("trial_result"));
        int trialVersion = number(current.get("trial_draft_version"));
        if (!"trial_passed".equals(current.get("status"))
                || !"success".equals(trial.get("status")) || trialVersion != expectedVersion) {
            throw new ImplementationException("DRAFT_TRIAL_EVIDENCE_STALE",
                    "只有当前版本 SQL 试运行通过后才能提交审批。", 409);
        }
        return transition(draftId, hospitalId, expectedVersion, "trial_passed",
                "pending_approval", actorId, "submitted", true);
    }

    Map<String, Object> workflowTransition(
            String draftId, String hospitalId, int expectedVersion, String expectedStatus,
            String nextStatus, Map<String, Object> changes, String actorId, String changeType) {
        Map<String, Object> safeChanges = changes == null ? Map.of() : changes;
        Set<String> unknown = new java.util.HashSet<>(safeChanges.keySet());
        unknown.removeAll(WORKFLOW_FIELDS);
        if (!unknown.isEmpty()) {
            throw new ImplementationException("DRAFT_WORKFLOW_FIELD_INVALID", "实施流程包含非法状态字段。", 400);
        }
        return transactions.execute(status -> {
            Map<String, Object> current = require(draftId, hospitalId);
            requireVersion(current, expectedVersion);
            if (!expectedStatus.equals(current.get("status"))) {
                throw new ImplementationException("DRAFT_STATUS_INVALID", "当前实施任务状态不允许执行该操作。", 409);
            }
            int nextVersion = expectedVersion + 1;
            List<Object> args = new ArrayList<>();
            StringBuilder assignments = new StringBuilder("status=?,current_version=?,updated_by=?,updated_at=?");
            LocalDateTime now = now();
            args.add(nextStatus); args.add(nextVersion); args.add(actorId); args.add(now);
            for (Map.Entry<String, Object> entry : safeChanges.entrySet()) {
                assignments.append(',').append(entry.getKey()).append("=?");
                args.add(JSON_FIELDS.contains(entry.getKey()) ? write(entry.getValue()) : entry.getValue());
            }
            args.add(draftId); args.add(hospitalId); args.add(expectedVersion);
            int changed = jdbc.update("UPDATE med_indicator_draft SET " + assignments
                    + " WHERE draft_id=? AND hospital_id=? AND current_version=?", args.toArray());
            if (changed != 1) throw conflict();
            Map<String, Object> saved = require(draftId, hospitalId);
            snapshot(saved, changeType, actorId, now);
            return saved;
        });
    }

    Map<String, Object> requireDraft(String draftId, String hospitalId) {
        return require(draftId, hospitalId);
    }

    public List<Map<String, Object>> versions(String draftId, String hospitalId) {
        require(draftId, hospitalId);
        return jdbc.query("""
                SELECT version,status,snapshot_json,change_type,oper_user,created_at
                FROM med_indicator_draft_version WHERE draft_id=? ORDER BY version DESC
                """, (result, ignored) -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("version", result.getInt("version"));
                    value.put("status", result.getString("status"));
                    value.put("snapshot", read(result.getString("snapshot_json"), Map.of()));
                    value.put("change_type", result.getString("change_type"));
                    value.put("oper_user", result.getString("oper_user"));
                    value.put("created_at", result.getObject("created_at"));
                    return value;
                }, draftId);
    }

    private Map<String, Object> transition(
            String draftId, String hospitalId, int expectedVersion, String expectedStatus,
            String nextStatus, String actorId, String changeType, boolean carryTrialEvidence) {
        return transactions.execute(status -> {
            Map<String, Object> current = require(draftId, hospitalId);
            requireVersion(current, expectedVersion);
            if (!expectedStatus.equals(current.get("status"))) {
                throw new ImplementationException("DRAFT_STATUS_INVALID", "当前实施任务状态不允许执行该操作。", 409);
            }
            int nextVersion = expectedVersion + 1;
            LocalDateTime now = now();
            int changed = carryTrialEvidence
                    ? jdbc.update("""
                            UPDATE med_indicator_draft SET status=?,current_version=?,trial_draft_version=?,
                              updated_by=?,updated_at=?
                            WHERE draft_id=? AND hospital_id=? AND current_version=?
                            """, nextStatus, nextVersion, nextVersion, actorId, now,
                            draftId, hospitalId, expectedVersion)
                    : jdbc.update("""
                            UPDATE med_indicator_draft SET status=?,current_version=?,updated_by=?,updated_at=?
                            WHERE draft_id=? AND hospital_id=? AND current_version=?
                            """, nextStatus, nextVersion, actorId, now,
                            draftId, hospitalId, expectedVersion);
            if (changed != 1) throw conflict();
            Map<String, Object> saved = require(draftId, hospitalId);
            snapshot(saved, changeType, actorId, now);
            return saved;
        });
    }

    private Map<String, Object> require(String draftId, String hospitalId) {
        return get(draftId, hospitalId).orElseThrow(() -> new ImplementationException(
                "DRAFT_NOT_FOUND", "指标实施任务不存在。", 404));
    }

    private static void requireVersion(Map<String, Object> current, int expectedVersion) {
        if (number(current.get("current_version")) != expectedVersion) throw conflict();
    }

    private void snapshot(Map<String, Object> draft, String changeType, String actorId, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO med_indicator_draft_version
                  (draft_id,version,status,snapshot_json,change_type,oper_user,created_at)
                VALUES (?,?,?,?,?,?,?)
                """, draft.get("draft_id"), draft.get("current_version"), draft.get("status"),
                write(draft), changeType, actorId, now);
    }

    private Map<String, Object> mapRow(ResultSet result, int ignored) throws SQLException {
        ResultSetMetaData metadata = result.getMetaData();
        Map<String, Object> row = new LinkedHashMap<>();
        for (int index = 1; index <= metadata.getColumnCount(); index++) {
            String key = metadata.getColumnLabel(index).toLowerCase(Locale.ROOT);
            int sqlType = metadata.getColumnType(index);
            Object value = JSON_FIELDS.contains(key)
                    ? read(result.getString(index), defaultJson(key))
                    : Set.of(Types.CLOB, Types.NCLOB, Types.LONGVARCHAR, Types.LONGNVARCHAR).contains(sqlType)
                            ? result.getString(index) : result.getObject(index);
            row.put(key, value);
        }
        return row;
    }

    private Object defaultJson(String field) {
        return "metadata_requirements".equals(field) ? List.of() : Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> raw ? (Map<String, Object>) raw : Map.of();
    }

    private Object read(String value, Object fallback) {
        if (value == null || value.isBlank()) return fallback;
        try { return json.readValue(value, new TypeReference<Object>() { }); }
        catch (RuntimeException exception) { return fallback; }
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (RuntimeException exception) {
            throw new ImplementationException("DRAFT_JSON_INVALID", "设计字段不是有效 JSON 数据。", 400);
        }
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue()
                : value == null || value.toString().isBlank() ? 0 : Integer.parseInt(value.toString());
    }

    private static LocalDateTime now() { return LocalDateTime.now().withNano(0); }
    private static ImplementationException conflict() {
        return new ImplementationException("DRAFT_VERSION_CONFLICT", "设计稿版本已变化，请刷新后重试。", 409);
    }
}
