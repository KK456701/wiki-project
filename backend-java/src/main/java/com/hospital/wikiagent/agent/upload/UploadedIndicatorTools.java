package com.hospital.wikiagent.agent.upload;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.runtime.ToolResult;
import com.hospital.wikiagent.agent.tools.ToolExecutionContext;
import com.hospital.wikiagent.details.IndicatorDetailException;
import com.hospital.wikiagent.details.IndicatorDetailService;
import com.hospital.wikiagent.details.UploadDetailComparator;
import com.hospital.wikiagent.details.UploadDetailComparator.RowComparison;
import com.hospital.wikiagent.upload.UploadStorage;
import com.hospital.wikiagent.upload.UploadStorage.UploadAccessException;
import com.hospital.wikiagent.upload.XlsxWorkbookReader;
import com.hospital.wikiagent.upload.XlsxWorkbookReader.NumericStats;
import com.hospital.wikiagent.upload.XlsxWorkbookReader.SheetPreview;
import com.hospital.wikiagent.upload.XlsxWorkbookReader.WorkbookPreview;
import com.hospital.wikiagent.upload.XlsxWorkbookReader.XlsxParseException;

@Component
public class UploadedIndicatorTools {
    private static final Set<String> NUMERATOR_ALIASES = Set.of(
            "分子", "numerator", "numeratorcount", "num");
    private static final Set<String> DENOMINATOR_ALIASES = Set.of(
            "分母", "denominator", "denominatorcount", "denom");
    private static final Set<String> RATE_ALIASES = Set.of(
            "指标率", "比例", "rate", "ratepct", "ratio", "percentage", "percent");

    private final UploadStorage storage;
    private final XlsxWorkbookReader reader;
    private final IndicatorDetailService detailService;
    private final UploadDetailComparator detailComparator;

    public UploadedIndicatorTools(UploadStorage storage, XlsxWorkbookReader reader) {
        this(storage, reader, null, null);
    }

    @Autowired
    public UploadedIndicatorTools(
            UploadStorage storage,
            XlsxWorkbookReader reader,
            IndicatorDetailService detailService,
            UploadDetailComparator detailComparator) {
        this.storage = storage;
        this.reader = reader;
        this.detailService = detailService;
        this.detailComparator = detailComparator;
    }

    public ToolResult analyze(Input input, ToolExecutionContext context) {
        UploadStorage.StoredUpload upload;
        try {
            upload = storage.requireOwned(input.fileKey(), context.agentContext().hospitalId());
        } catch (UploadAccessException exception) {
            return ToolResult.failure(
                    "UPLOAD_ACCESS_DENIED".equals(exception.code()) ? "forbidden" : "not_found",
                    exception.code(), exception.getMessage(), false);
        }

        WorkbookPreview workbook;
        try {
            workbook = reader.read(upload);
        } catch (XlsxParseException exception) {
            return ToolResult.failure(
                    "validation_failed", "EXCEL_PARSE_ERROR", exception.getMessage(), false);
        }

        DetectedValues uploadedValues = detectValues(workbook);
        TrialValues systemValues = latestTrial(context);
        FileIdentity identity = fileIdentity(workbook);
        Comparison comparison = compare(identity, uploadedValues, systemValues);
        RowComparison rowComparison = rowComparison(workbook, systemValues, context);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("file_key", upload.fileKey());
        data.put("file_name", upload.originalName());
        data.put("sheet_count", workbook.sheets().size());
        data.put("row_count", workbook.totalRows());
        data.put("columns", safeColumns(workbook));
        data.put("summary", "已解析 " + upload.originalName() + "，共 "
                + workbook.totalRows() + " 行数据。");
        data.put("looks_like_indicator_data", uploadedValues.hasAny() || identity.ruleId() != null);
        put(data, "uploaded_rule_id", identity.ruleId());
        put(data, "uploaded_rule_name", identity.ruleName());
        put(data, "uploaded_stat_period", identity.statPeriod());
        put(data, "uploaded_numerator", uploadedValues.numerator());
        put(data, "uploaded_denominator", uploadedValues.denominator());
        put(data, "uploaded_rate", uploadedValues.rate());
        put(data, "system_rule_id", systemValues.ruleId());
        put(data, "system_stat_period", systemValues.statPeriod());
        put(data, "system_numerator", systemValues.numerator());
        put(data, "system_denominator", systemValues.denominator());
        put(data, "system_rate", systemValues.rate());
        data.put("comparison_level", comparison.level());
        data.put("comparison_status", comparison.status());
        data.put("comparison_direction", "上传文件值 - 系统值");
        data.put("comparison_metrics", comparison.metrics());
        data.put("matched_count", comparison.matchedCount());
        data.put("different_count", comparison.differentCount());
        if (rowComparison == null) {
            data.put("row_level_comparison_available", false);
            data.put("cause_analysis_available", false);
            data.put("cause_analysis_note", comparison.note());
        } else {
            Map<String, Object> safeRowComparison = rowComparison.safeData();
            data.putAll(safeRowComparison);
            data.put("row_comparison", safeRowComparison);
            data.put("cause_analysis_available", rowComparison.available());
            data.put("cause_analysis_note", rowComparison.message());
        }

        String summary = rowComparison == null
                ? comparison.summary()
                : rowComparison.available()
                        ? "已完成上传文件与系统明细逐条对比：双方都有 "
                                + rowComparison.bothCount() + " 条，仅系统有 "
                                + rowComparison.systemOnlyCount() + " 条，仅上传文件有 "
                                + rowComparison.uploadedOnlyCount() + " 条。"
                        : rowComparison.message();
        if (summary == null || summary.isBlank()) {
            summary = String.valueOf(data.get("summary"));
        }
        return ToolResult.success("UPLOAD_ANALYZED", summary, data);
    }

    private static DetectedValues detectValues(WorkbookPreview workbook) {
        Double numerator = null;
        Double denominator = null;
        Double rate = null;
        String numeratorColumn = null;
        String denominatorColumn = null;
        String rateColumn = null;
        for (SheetPreview sheet : workbook.sheets()) {
            for (Map.Entry<String, NumericStats> entry : sheet.numericColumns().entrySet()) {
                String normalized = normalizeHeader(entry.getKey());
                Double candidate = singleValue(entry.getValue());
                if (numerator == null && NUMERATOR_ALIASES.contains(normalized)) {
                    numerator = candidate;
                    numeratorColumn = entry.getKey();
                } else if (denominator == null && DENOMINATOR_ALIASES.contains(normalized)) {
                    denominator = candidate;
                    denominatorColumn = entry.getKey();
                } else if (rate == null && RATE_ALIASES.contains(normalized)) {
                    rate = candidate;
                    rateColumn = entry.getKey();
                }
            }
        }
        if (rate != null && rate >= 0 && rate <= 1 && numerator != null && denominator != null
                && denominator != 0) {
            double calculated = numerator / denominator * 100.0;
            if (Math.abs(rate * 100.0 - calculated) < Math.abs(rate - calculated)) {
                rate *= 100.0;
            }
        }
        return new DetectedValues(
                rounded(numerator), rounded(denominator), rounded(rate),
                numeratorColumn, denominatorColumn, rateColumn);
    }

    private static TrialValues latestTrial(ToolExecutionContext context) {
        List<ToolResult> results = context.runState().lastToolResults();
        for (int index = results.size() - 1; index >= 0; index--) {
            ToolResult result = results.get(index);
            if (!result.ok() || !"TRIAL_RUN_COMPLETED".equals(result.code())) {
                continue;
            }
            Map<String, Object> data = result.data();
            return new TrialValues(
                    text(data.get("run_id")),
                    text(data.get("rule_id")),
                    period(data.get("stat_start"), data.get("stat_end")),
                    number(data.get("numerator_count")),
                    number(data.get("denominator_count")),
                    number(data.get("result_value")));
        }
        return TrialValues.empty();
    }

    private RowComparison rowComparison(
            WorkbookPreview workbook,
            TrialValues systemValues,
            ToolExecutionContext context) {
        boolean hasDetailSheet = workbook.sheets().stream().anyMatch(SheetPreview::detailExport);
        if (!hasDetailSheet || systemValues.runId() == null
                || detailService == null || detailComparator == null) {
            return null;
        }
        try {
            var dataset = detailService.comparisonDataset(
                    context.agentContext().principal(), systemValues.runId());
            return detailComparator.compare(workbook, dataset);
        } catch (IndicatorDetailException exception) {
            return null;
        }
    }

    private static FileIdentity fileIdentity(WorkbookPreview workbook) {
        for (SheetPreview sheet : workbook.sheets()) {
            if (sheet.metadata().isEmpty()) {
                continue;
            }
            String ruleId = text(first(sheet.metadata(), "指标编号", "指标编码"));
            String ruleName = text(sheet.metadata().get("指标名称"));
            String statPeriod = text(sheet.metadata().get("统计区间"));
            return new FileIdentity(ruleId, ruleName, statPeriod);
        }
        return FileIdentity.empty();
    }

    private static Comparison compare(
            FileIdentity identity,
            DetectedValues uploaded,
            TrialValues system) {
        if (!system.hasValues()) {
            return new Comparison(
                    "none", "system_result_missing", List.of(), 0, 0,
                    "已完成文件结构与汇总值识别；本轮没有系统试运行结果，暂不能进行数值对比。",
                    "本轮未获得同一指标、同一统计周期的系统试运行证据。");
        }
        if (identity.ruleId() != null && system.ruleId() != null
                && !identity.ruleId().equalsIgnoreCase(system.ruleId())) {
            return new Comparison(
                    "none", "indicator_mismatch", List.of(), 0, 0,
                    "上传文件与本轮系统结果属于不同指标，已停止比较。",
                    "上传文件指标编号为 " + identity.ruleId()
                            + "，系统结果指标编号为 " + system.ruleId() + "。");
        }
        List<Map<String, Object>> metrics = new ArrayList<>();
        addMetric(metrics, "分母", "denominator", uploaded.denominator(), system.denominator(), "人次");
        addMetric(metrics, "分子", "numerator", uploaded.numerator(), system.numerator(), "人次");
        addMetric(metrics, "指标率", "rate", uploaded.rate(), system.rate(), "百分点");
        int matched = (int) metrics.stream().filter(item -> Boolean.TRUE.equals(item.get("match"))).count();
        int different = metrics.size() - matched;
        String periodNote = identity.statPeriod() != null && system.statPeriod() != null
                && !canonicalPeriod(identity.statPeriod()).equals(canonicalPeriod(system.statPeriod()))
                ? "已确认统计区间文本不一致：上传文件为 " + identity.statPeriod()
                        + "；系统为 " + system.statPeriod() + "。"
                : "";
        String note = periodNote.isBlank()
                ? "当前只能确认汇总数值是否一致；文件未与系统明细逐条关联，不能推测重复记录、ICU 排除或字段映射是差异原因。"
                : periodNote + " 当前尚未执行逐条业务记录比较。";
        return new Comparison(
                "aggregate",
                metrics.isEmpty() ? "aggregate_values_missing" : "aggregate_compared",
                List.copyOf(metrics), matched, different,
                metrics.isEmpty()
                        ? "文件中未识别到分子、分母或指标率列。"
                        : "已完成上传文件与系统结果的汇总级对比：一致 " + matched
                                + " 项，不一致 " + different + " 项。",
                note);
    }

    private static void addMetric(
            List<Map<String, Object>> values,
            String label,
            String role,
            Double uploaded,
            Double system,
            String unit) {
        if (uploaded == null || system == null) {
            return;
        }
        double difference = rounded(uploaded - system);
        Map<String, Object> metric = new LinkedHashMap<>();
        metric.put("metric", label);
        metric.put("role", role);
        metric.put("uploaded_value", uploaded);
        metric.put("system_value", system);
        metric.put("difference", difference);
        metric.put("unit", unit);
        metric.put("match", Math.abs(difference) < 0.01);
        values.add(Map.copyOf(metric));
    }

    private static List<String> safeColumns(WorkbookPreview workbook) {
        List<String> result = new ArrayList<>();
        for (SheetPreview sheet : workbook.sheets()) {
            for (String header : sheet.headers()) {
                if (!header.isBlank() && !result.contains(header)) {
                    result.add(header.length() > 128 ? header.substring(0, 128) : header);
                }
                if (result.size() >= 30) {
                    return List.copyOf(result);
                }
            }
        }
        return List.copyOf(result);
    }

    private static String normalizeHeader(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s_（）()％%\\-]", "");
    }

    private static Double singleValue(NumericStats stats) {
        return rounded(stats.count() == 1 ? stats.sum() : stats.average());
    }

    private static Double number(Object value) {
        if (value instanceof Number number) {
            return rounded(number.doubleValue());
        }
        try {
            return value == null ? null : rounded(Double.parseDouble(String.valueOf(value)));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static double rounded(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    private static Double rounded(Double value) {
        return value == null ? null : rounded(value.doubleValue());
    }

    private static String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).strip();
    }

    private static Object first(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            if (values.get(key) != null) {
                return values.get(key);
            }
        }
        return null;
    }

    private static String period(Object start, Object end) {
        return start == null || end == null ? null : start + " 至 " + end;
    }

    private static String canonicalPeriod(String value) {
        return value.replaceAll("[\\s~～至]", "");
    }

    private static void put(Map<String, Object> values, String key, Object value) {
        if (value != null) {
            values.put(key, value);
        }
    }

    public record Input(String fileKey) {
        public Input {
            fileKey = fileKey == null ? "" : fileKey.strip();
            if (fileKey.isEmpty() || fileKey.length() > 255
                    || fileKey.contains("/") || fileKey.contains("\\")) {
                throw new IllegalArgumentException("上传文件编号不符合安全约束");
            }
        }
    }

    private record DetectedValues(
            Double numerator,
            Double denominator,
            Double rate,
            String numeratorColumn,
            String denominatorColumn,
            String rateColumn) {
        boolean hasAny() {
            return numerator != null || denominator != null || rate != null;
        }
    }

    private record TrialValues(
            String runId,
            String ruleId,
            String statPeriod,
            Double numerator,
            Double denominator,
            Double rate) {
        static TrialValues empty() {
            return new TrialValues(null, null, null, null, null, null);
        }

        boolean hasValues() {
            return numerator != null || denominator != null || rate != null;
        }
    }

    private record FileIdentity(String ruleId, String ruleName, String statPeriod) {
        static FileIdentity empty() {
            return new FileIdentity(null, null, null);
        }
    }

    private record Comparison(
            String level,
            String status,
            List<Map<String, Object>> metrics,
            int matchedCount,
            int differentCount,
            String summary,
            String note) {
    }
}
