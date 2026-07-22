package com.hospital.wikiagent.details;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.hospital.wikiagent.details.DetailContracts.DetailColumn;
import com.hospital.wikiagent.details.DetailContracts.SnapshotSummary;
import com.hospital.wikiagent.upload.XlsxWorkbookReader.SheetPreview;
import com.hospital.wikiagent.upload.XlsxWorkbookReader.WorkbookPreview;

/**
 * 上传明细与系统快照的确定性多重集合比较器。
 *
 * <p>原始行只存在于该对象和受保护的导出流程中；调用方必须使用
 * {@link RowComparison#safeData()} 向 LLM、Evidence 或 Trace 暴露结果。</p>
 *
 * <p>该类型在所属包边界内完成单一领域职责，并通过构造器显式接收依赖。涉及外部 I/O、权限或患者数据时，必须复用现有网关和安全对象，不能在此处建立旁路。</p>
 */
@Component
public class UploadDetailComparator {
    private static final List<String> IDENTITY_TERMS = List.of(
            "患者标识", "入院流水号", "admission_id", "申请编号", "会诊编号", "记录编号",
            "consult_id", "transfer_id", "request_id");
    private static final List<String> EVENT_TIME_TERMS = List.of(
            "申请时间", "请求时间", "入院时间", "转科时间", "发生时间", "request_time",
            "admit_time", "transfer_time");
    private static final Set<String> NON_KEY_FIELDS = Set.of(
            "是否达到要求", "到位耗时（分钟）", "转科耗时（分钟）");

    public RowComparison compare(WorkbookPreview workbook, SystemDetailDataset system) {
        SheetPreview uploadedSheet = primaryDetailSheet(workbook);
        SnapshotSummary summary = system.summary();
        if (uploadedSheet == null) {
            return RowComparison.unavailable(
                    "detail_sheet_missing", summary, null, null, null,
                    "上传文件不包含可识别的指标明细工作表。");
        }
        String uploadedRuleId = text(first(uploadedSheet.metadata(), "指标编号", "指标编码"));
        String uploadedRuleName = text(uploadedSheet.metadata().get("指标名称"));
        String uploadedHospitalId = text(uploadedSheet.metadata().get("适用医院"));
        String uploadedPeriod = text(uploadedSheet.metadata().get("统计区间"));
        if (uploadedRuleId == null || summary.ruleId() == null) {
            return RowComparison.unavailable(
                    "identity_missing", summary, uploadedRuleId, uploadedRuleName, uploadedPeriod,
                    "上传文件或系统结果缺少指标编号，不能进行逐条对比。");
        }
        if (!uploadedRuleId.equalsIgnoreCase(summary.ruleId())) {
            return RowComparison.unavailable(
                    "indicator_mismatch", summary, uploadedRuleId, uploadedRuleName, uploadedPeriod,
                    "上传文件属于“" + fallback(uploadedRuleName, uploadedRuleId) + "”(" + uploadedRuleId
                            + ")，当前查询属于“" + fallback(summary.ruleName(), summary.ruleId()) + "”("
                            + summary.ruleId() + ")，两个指标不能进行逐条比较。");
        }
        if (uploadedHospitalId != null && !uploadedHospitalId.equals(summary.hospitalId())) {
            return RowComparison.unavailable(
                    "hospital_mismatch", summary, uploadedRuleId, uploadedRuleName, uploadedPeriod,
                    "上传文件与当前系统结果属于不同医院，不能进行逐条比较。");
        }

        List<Map<String, Object>> uploadedRows = uploadedRows(uploadedSheet);
        List<Map<String, Object>> systemRows = systemRows(system);
        List<String> uploadedHeaders = uploadedSheet.headers().stream()
                .filter(value -> value != null && !value.isBlank()).toList();
        List<String> systemHeaders = new ArrayList<>();
        summary.columns().forEach(column -> systemHeaders.add(column.label()));
        systemHeaders.add("是否达到要求");
        List<String> commonFields = systemHeaders.stream().filter(uploadedHeaders::contains).toList();
        List<String> matchingFields = matchingFields(commonFields);
        if (matchingFields.isEmpty()) {
            return RowComparison.unavailable(
                    "matching_fields_missing", summary, uploadedRuleId, uploadedRuleName, uploadedPeriod,
                    "上传文件与系统明细没有可用于识别同一业务记录的公共字段。");
        }

        Map<List<String>, Deque<Map<String, Object>>> uploadedByKey = new LinkedHashMap<>();
        for (Map<String, Object> row : uploadedRows) {
            uploadedByKey.computeIfAbsent(rowKey(row, matchingFields), ignored -> new ArrayDeque<>())
                    .addLast(row);
        }
        List<MatchedRow> matched = new ArrayList<>();
        List<Map<String, Object>> systemOnly = new ArrayList<>();
        for (Map<String, Object> systemRow : systemRows) {
            List<String> key = rowKey(systemRow, matchingFields);
            Deque<Map<String, Object>> candidates = uploadedByKey.get(key);
            if (candidates == null || candidates.isEmpty()) {
                systemOnly.add(systemRow);
                continue;
            }
            Map<String, Object> uploadedRow = candidates.removeFirst();
            List<String> differences = commonFields.stream()
                    .filter(field -> !canonical(systemRow.get(field)).equals(canonical(uploadedRow.get(field))))
                    .toList();
            matched.add(new MatchedRow(String.join(" | ", key), systemRow, uploadedRow, differences));
        }
        List<Map<String, Object>> uploadedOnly = uploadedByKey.values().stream()
                .flatMap(Deque::stream).toList();
        int changed = (int) matched.stream().filter(item -> !item.differentFields().isEmpty()).count();
        int systemNumerator = (int) systemRows.stream().filter(UploadDetailComparator::meets).count();
        int uploadedNumerator = (int) uploadedRows.stream().filter(UploadDetailComparator::meets).count();
        int systemOnlyNumerator = (int) systemOnly.stream().filter(UploadDetailComparator::meets).count();
        int uploadedOnlyNumerator = (int) uploadedOnly.stream().filter(UploadDetailComparator::meets).count();
        int classificationDifferences = (int) matched.stream()
                .filter(item -> meets(item.system()) != meets(item.uploaded())).count();
        List<String> findings = findings(summary, uploadedPeriod, matched.size(), systemOnly.size(),
                uploadedOnly.size(), changed, systemNumerator, uploadedNumerator,
                systemOnlyNumerator, uploadedOnlyNumerator, classificationDifferences);
        List<String> systemOnlyFields = systemHeaders.stream().filter(field -> !uploadedHeaders.contains(field)).toList();
        List<String> uploadedOnlyFields = uploadedHeaders.stream().filter(field -> !systemHeaders.contains(field)).toList();
        return new RowComparison(
                "row_level_compared", true, summary.ruleId(), summary.ruleName(), uploadedRuleId,
                uploadedRuleName, systemPeriod(summary), uploadedPeriod, matchingFields, commonFields,
                systemOnlyFields, uploadedOnlyFields, systemRows.size(), uploadedRows.size(), matched.size(),
                systemOnly.size(), uploadedOnly.size(), changed, systemNumerator, uploadedNumerator,
                systemOnlyNumerator, uploadedOnlyNumerator, classificationDifferences, findings,
                "已完成逐条记录对比；可确认记录交集、差集和字段/达标判定差异，但不会推测未被数据证明的业务根因。",
                List.copyOf(matched), immutableRows(systemOnly), immutableRows(uploadedOnly));
    }

    private static SheetPreview primaryDetailSheet(WorkbookPreview workbook) {
        return workbook.sheets().stream()
                .filter(SheetPreview::detailExport)
                .filter(sheet -> sheet.name().startsWith("统计范围"))
                .findFirst()
                .orElseGet(() -> workbook.sheets().stream()
                        .filter(SheetPreview::detailExport).findFirst().orElse(null));
    }

    private static List<Map<String, Object>> uploadedRows(SheetPreview sheet) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (List<Object> values : sheet.rows()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int index = 0; index < sheet.headers().size(); index++) {
                row.put(sheet.headers().get(index), index < values.size() ? values.get(index) : null);
            }
            result.add(Collections.unmodifiableMap(row));
        }
        return List.copyOf(result);
    }

    private static List<Map<String, Object>> systemRows(SystemDetailDataset system) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> raw : system.rows()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (DetailColumn column : system.summary().columns()) {
                row.put(column.label(), raw.get(column.field()));
            }
            row.put("是否达到要求", rawMeets(raw) ? "是" : "否");
            result.add(Collections.unmodifiableMap(row));
        }
        return List.copyOf(result);
    }

    private static List<String> matchingFields(List<String> commonFields) {
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        commonFields.stream().filter(field -> containsTerm(field, IDENTITY_TERMS)).forEach(selected::add);
        commonFields.stream().filter(field -> containsTerm(field, EVENT_TIME_TERMS)).forEach(selected::add);
        if (!selected.isEmpty()) {
            return List.copyOf(selected);
        }
        return commonFields.stream().filter(field -> !NON_KEY_FIELDS.contains(field)).toList();
    }

    private static boolean containsTerm(String field, List<String> terms) {
        String normalized = field.toLowerCase(Locale.ROOT);
        return terms.stream().map(value -> value.toLowerCase(Locale.ROOT)).anyMatch(normalized::contains);
    }

    private static List<String> rowKey(Map<String, Object> row, List<String> fields) {
        return fields.stream().map(field -> canonical(row.get(field))).toList();
    }

    private static String canonical(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        if (value instanceof Number number) {
            try {
                return new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
            } catch (NumberFormatException ignored) {
                return number.toString();
            }
        }
        String text = String.valueOf(value).strip();
        if (text.matches("\\d{4}-\\d{2}-\\d{2}[ T].*")) {
            try {
                return OffsetDateTime.parse(text.replace(' ', 'T')).toLocalDateTime().toString();
            } catch (DateTimeParseException ignored) {
                try {
                    return LocalDateTime.parse(text.replace(' ', 'T')).toString();
                } catch (DateTimeParseException ignoredAgain) {
                    // 数据库和工作簿都可能使用不带 ISO 偏移的文本；保留原值继续比较。
                }
            }
        }
        return text;
    }

    private static List<String> findings(
            SnapshotSummary summary, String uploadedPeriod, int both, int systemOnly,
            int uploadedOnly, int changed, int systemNumerator, int uploadedNumerator,
            int systemOnlyNumerator, int uploadedOnlyNumerator, int classificationDifferences) {
        List<String> values = new ArrayList<>();
        if (uploadedPeriod != null && !canonicalPeriod(uploadedPeriod).equals(canonicalPeriod(systemPeriod(summary)))) {
            values.add("统计区间文本不一致：系统为 " + systemPeriod(summary)
                    + "；上传文件为 " + uploadedPeriod + "。");
        }
        values.add("双方都有 " + both + " 条；仅系统有 " + systemOnly
                + " 条；仅上传文件有 " + uploadedOnly + " 条。");
        if (changed > 0) {
            values.add("双方匹配记录中有 " + changed + " 条存在字段值差异。");
        }
        values.add("达到要求记录：系统 " + systemNumerator + " 条、上传文件 " + uploadedNumerator
                + " 条；其中仅系统有 " + systemOnlyNumerator + " 条、仅上传文件有 "
                + uploadedOnlyNumerator + " 条，双方同一记录但判定不同 "
                + classificationDifferences + " 条。");
        return List.copyOf(values);
    }

    private static String canonicalPeriod(String value) {
        return value == null ? "" : value.replaceAll("[\\s~～至（）()不含结束时刻]", "");
    }

    private static String systemPeriod(SnapshotSummary summary) {
        return summary.statStart() + " 至 " + summary.statEnd();
    }

    private static boolean meets(Map<String, Object> row) {
        return Set.of("是", "1", "true", "yes", "y")
                .contains(canonical(row.get("是否达到要求")).toLowerCase(Locale.ROOT));
    }

    private static boolean rawMeets(Map<String, Object> row) {
        Object value = row.get("__meets_numerator");
        return value instanceof Number number ? number.intValue() == 1
                : "1".equals(String.valueOf(value)) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static Object first(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            if (values.get(key) != null) {
                return values.get(key);
            }
        }
        return null;
    }

    private static String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).strip();
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static List<Map<String, Object>> immutableRows(List<Map<String, Object>> rows) {
        return rows.stream().map(row -> Collections.unmodifiableMap(new LinkedHashMap<>(row))).toList();
    }

    public record SystemDetailDataset(SnapshotSummary summary, List<Map<String, Object>> rows) {
        public SystemDetailDataset {
            if (summary == null) {
                throw new IllegalArgumentException("系统明细摘要不能为空");
            }
            rows = immutableRows(rows == null ? List.of() : rows);
        }
    }

    public record MatchedRow(
            String key,
            Map<String, Object> system,
            Map<String, Object> uploaded,
            List<String> differentFields) {
        public MatchedRow {
            system = Collections.unmodifiableMap(new LinkedHashMap<>(system));
            uploaded = Collections.unmodifiableMap(new LinkedHashMap<>(uploaded));
            differentFields = List.copyOf(differentFields);
        }
    }

    public record RowComparison(
            String status,
            boolean available,
            String systemRuleId,
            String systemRuleName,
            String uploadedRuleId,
            String uploadedRuleName,
            String systemStatPeriod,
            String uploadedStatPeriod,
            List<String> matchingFields,
            List<String> commonFields,
            List<String> systemOnlyFields,
            List<String> uploadedOnlyFields,
            int systemCount,
            int uploadedCount,
            int bothCount,
            int systemOnlyCount,
            int uploadedOnlyCount,
            int fieldDifferenceCount,
            int systemNumeratorCount,
            int uploadedNumeratorCount,
            int systemOnlyNumeratorCount,
            int uploadedOnlyNumeratorCount,
            int classificationDifferenceCount,
            List<String> confirmedFindings,
            String message,
            List<MatchedRow> matchedRows,
            List<Map<String, Object>> systemOnlyRows,
            List<Map<String, Object>> uploadedOnlyRows) {
        public RowComparison {
            matchingFields = List.copyOf(matchingFields);
            commonFields = List.copyOf(commonFields);
            systemOnlyFields = List.copyOf(systemOnlyFields);
            uploadedOnlyFields = List.copyOf(uploadedOnlyFields);
            confirmedFindings = List.copyOf(confirmedFindings);
            matchedRows = List.copyOf(matchedRows);
            systemOnlyRows = immutableRows(systemOnlyRows);
            uploadedOnlyRows = immutableRows(uploadedOnlyRows);
        }

        static RowComparison unavailable(
                String status, SnapshotSummary system, String uploadedRuleId,
                String uploadedRuleName, String uploadedPeriod, String message) {
            return new RowComparison(
                    status, false, system.ruleId(), system.ruleName(), uploadedRuleId,
                    uploadedRuleName, systemPeriod(system), uploadedPeriod, List.of(), List.of(),
                    List.of(), List.of(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    List.of(), message, List.of(), List.of(), List.of());
        }

        public Map<String, Object> safeData() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("comparison_status", status);
            values.put("comparison_level", available ? "row" : "none");
            values.put("row_level_comparison_available", available);
            put(values, "system_rule_id", systemRuleId);
            put(values, "system_rule_name", systemRuleName);
            put(values, "uploaded_rule_id", uploadedRuleId);
            put(values, "uploaded_rule_name", uploadedRuleName);
            put(values, "system_stat_period", systemStatPeriod);
            put(values, "uploaded_stat_period", uploadedStatPeriod);
            values.put("matching_fields", matchingFields);
            values.put("common_fields", commonFields);
            values.put("system_only_fields", systemOnlyFields);
            values.put("uploaded_only_fields", uploadedOnlyFields);
            values.put("system_count", systemCount);
            values.put("uploaded_count", uploadedCount);
            values.put("both_count", bothCount);
            values.put("system_only_count", systemOnlyCount);
            values.put("uploaded_only_count", uploadedOnlyCount);
            values.put("field_difference_count", fieldDifferenceCount);
            values.put("system_numerator_count", systemNumeratorCount);
            values.put("uploaded_numerator_count", uploadedNumeratorCount);
            values.put("system_only_numerator_count", systemOnlyNumeratorCount);
            values.put("uploaded_only_numerator_count", uploadedOnlyNumeratorCount);
            values.put("classification_difference_count", classificationDifferenceCount);
            values.put("confirmed_findings", confirmedFindings);
            values.put("message", message);
            return Collections.unmodifiableMap(values);
        }

        private static void put(Map<String, Object> values, String key, Object value) {
            if (value != null) {
                values.put(key, value);
            }
        }
    }
}
