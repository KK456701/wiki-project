package com.hospital.wikiagent.implementation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DraftMetadataService {
    private final JdbcTemplate jdbc;
    private final IndicatorDraftRepository drafts;

    public DraftMetadataService(JdbcTemplate jdbc, IndicatorDraftRepository drafts) {
        this.jdbc = jdbc;
        this.drafts = drafts;
    }

    public Map<String, Object> suggestions(String draftId, String hospitalId) {
        Map<String, Object> draft = drafts.requireDraft(draftId, hospitalId);
        String mainTable = text(map(draft.get("sql_plan")).get("main_table"));
        if (mainTable.isBlank()) {
            throw new ImplementationException("DRAFT_MAIN_TABLE_REQUIRED", "设计稿缺少统计主表。", 409);
        }
        List<Map<String, Object>> columns = jdbc.queryForList("""
                SELECT db_name,table_name,column_name,data_type,column_comment
                FROM med_metadata_column
                WHERE hospital_id=? AND table_name=?
                ORDER BY db_name,column_name
                """, hospitalId, mainTable);
        Map<String, List<Map<String, Object>>> suggestions = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        List<String> ambiguous = new ArrayList<>();
        for (String businessField : strings(draft.get("metadata_requirements"))) {
            List<Map<String, Object>> candidates = columns.stream()
                    .map(DraftMetadataService::normalizeKeys)
                    .filter(row -> matches(row, businessField))
                    .map(row -> candidate(row, businessField))
                    .toList();
            suggestions.put(businessField, candidates);
            if (candidates.isEmpty()) missing.add(businessField);
            else if (candidates.size() > 1) ambiguous.add(businessField);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("draft_id", draftId);
        result.put("hospital_id", hospitalId);
        result.put("main_table", mainTable);
        result.put("suggestions", suggestions);
        result.put("missing_fields", missing);
        result.put("ambiguous_fields", ambiguous);
        result.put("ready_for_confirmation", missing.isEmpty());
        return result;
    }

    public Map<String, Object> confirm(
            String draftId, String hospitalId, int expectedVersion,
            Map<String, Map<String, Object>> mappings, String actorId) {
        Map<String, Object> draft = drafts.requireDraft(draftId, hospitalId);
        if (!"metadata_pending".equals(draft.get("status"))) {
            throw new ImplementationException("DRAFT_STATUS_INVALID",
                    "请先确认指标取数要求，再映射医院表和字段。", 409);
        }
        List<String> requirements = strings(draft.get("metadata_requirements"));
        List<String> missing = requirements.stream()
                .filter(field -> mappings == null || !mappings.containsKey(field)).toList();
        if (!missing.isEmpty()) {
            throw new ImplementationException("DRAFT_MAPPING_INCOMPLETE", "字段映射不完整：" + missing, 400);
        }
        String mainTable = text(map(draft.get("sql_plan")).get("main_table"));
        if (mainTable.isBlank()) {
            throw new ImplementationException("DRAFT_MAIN_TABLE_REQUIRED", "设计稿缺少统计主表。", 409);
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (String businessField : requirements) {
            Map<String, Object> item = mappings.get(businessField);
            if (item == null) {
                throw new ImplementationException("DRAFT_MAPPING_INCOMPLETE", "字段映射不完整：" + businessField, 400);
            }
            String database = text(item.get("db_name"));
            String table = text(item.get("table_name"));
            String column = text(item.get("column_name"));
            if (database.isBlank() || !mainTable.equals(table) || column.isBlank()) {
                throw new ImplementationException("DRAFT_MAPPING_TABLE_INVALID",
                        "第一版字段映射必须完整且来自已确认的单一主表。", 400);
            }
            List<String> types = jdbc.query("""
                    SELECT data_type FROM med_metadata_column
                    WHERE hospital_id=? AND db_name=? AND table_name=? AND column_name=?
                    """, (result, ignored) -> result.getString(1), hospitalId, database, table, column);
            if (types.isEmpty()) {
                throw new ImplementationException("DRAFT_MAPPING_NOT_IN_SNAPSHOT",
                        "字段不在最近元数据快照中：" + businessField, 409);
            }
            normalized.put(businessField, Map.of(
                    "hospital_id", hospitalId,
                    "db_name", database,
                    "table_name", table,
                    "column_name", column,
                    "data_type", text(types.get(0)),
                    "status", "confirmed"));
        }
        return drafts.workflowTransition(draftId, hospitalId, expectedVersion,
                "metadata_pending", "metadata_ready", Map.of("field_mapping", normalized),
                actorId, "metadata_confirmed");
    }

    private static boolean matches(Map<String, Object> row, String field) {
        String expected = normalized(field);
        String column = normalized(row.get("column_name"));
        String comment = normalized(row.get("column_comment"));
        return column.equals(expected) || (!comment.isBlank() && (comment.equals(expected) || comment.contains(expected)));
    }

    private static Map<String, Object> candidate(Map<String, Object> row, String field) {
        boolean exact = normalized(row.get("column_name")).equals(normalized(field));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("db_name", text(row.get("db_name")));
        result.put("table_name", text(row.get("table_name")));
        result.put("column_name", text(row.get("column_name")));
        result.put("data_type", text(row.get("data_type")));
        result.put("confidence", exact ? 1.0 : 0.8);
        result.put("reason", exact ? "字段名完全匹配" : "字段注释匹配");
        return result;
    }

    private static Map<String, Object> normalizeKeys(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        row.forEach((key, value) -> result.put(key.toLowerCase(Locale.ROOT), value));
        return result;
    }

    private static String normalized(Object value) {
        return text(value).toLowerCase(Locale.ROOT).replace("_", "");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> raw ? (Map<String, Object>) raw : Map.of();
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> values)) return List.of();
        return values.stream().map(DraftMetadataService::text).filter(item -> !item.isBlank()).toList();
    }

    private static String text(Object value) { return value == null ? "" : value.toString().strip(); }
}
