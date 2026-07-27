package com.hospital.wikiagent.agent.diagnosis;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.runtime.ToolResult;
import com.hospital.wikiagent.agent.sql.IndicatorBusinessQueryClient;
import com.hospital.wikiagent.agent.sql.IndicatorSqlTools;
import com.hospital.wikiagent.agent.tools.ToolExecutionContext;
import com.hospital.wikiagent.rules.RuleReadRepository;

/**
 * 提供 {@code IndicatorDiagnosisTools} 对应的受控 Agent 工具能力。
 *
 * <p>能力只能经 ToolGateway 的权限、参数和重复调用检查后执行，不能由模型绕过网关直接调用。返回值必须形成可验证 Evidence，再交给最终答案使用。</p>
 */
@Component
public class IndicatorDiagnosisTools {
    private final RuleReadRepository rules;
    private final IndicatorSqlTools sqlTools;
    private final IndicatorBusinessQueryClient businessQuery;
    private final DiagnosisReportRepository reports;

    public IndicatorDiagnosisTools(
            RuleReadRepository rules,
            IndicatorSqlTools sqlTools,
            IndicatorBusinessQueryClient businessQuery,
            DiagnosisReportRepository reports) {
        this.rules = rules;
        this.sqlTools = sqlTools;
        this.businessQuery = businessQuery;
        this.reports = reports;
    }

    public ToolResult diagnose(Input input, ToolExecutionContext context) {
        if (context.runState().currentRuleId() == null
                || !context.runState().currentRuleId().equals(input.ruleId())) {
            return failure("validation_failed", "RULE_NOT_VERIFIED",
                    "该指标尚未经过规则搜索或读取，不能启动诊断。", false);
        }
        if (input.profileId() != null) {
            return diagnoseProfile(input, context);
        }
        List<Map<String, Object>> profiles = rules.caliberProfiles(
                input.ruleId(), context.agentContext().hospitalId());
        if (profiles.isEmpty()) {
            return failure("unavailable", "PROFILE_NOT_EXECUTABLE",
                    "当前指标没有可诊断的已审批 Profile。", false);
        }
        List<Map<String, Object>> profileReports = new ArrayList<>();
        for (Map<String, Object> profile : profiles) {
            String profileId = text(profile.get("profile_id"));
            String profileName = first(
                    text(profile.get("profile_name")),
                    text(profile.get("label")),
                    profileId);
            ToolResult result = diagnoseProfile(
                    new Input(
                            input.ruleId(), input.issueDescription(),
                            input.statPeriod(), profileId),
                    context);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("profile_id", profileId);
            item.put("profile_name", profileName);
            item.put("ok", result.ok());
            item.put("code", result.code());
            item.put("summary", result.summary());
            item.put("report", result.data());
            profileReports.add(item);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rule_id", input.ruleId());
        data.put("approved_profile_count", profileReports.size());
        data.put("profile_reports", profileReports);
        data.put("summary", "已逐一诊断 " + profileReports.size() + " 个已审批 Profile。");
        return ToolResult.success(
                "INDICATOR_DIAGNOSED",
                "指标全部已审批 Profile 诊断已完成。",
                data);
    }

    private ToolResult diagnoseProfile(Input input, ToolExecutionContext context) {
        context.runState().currentCaliber(input.profileId(), input.profileId());
        Map<String, Object> rule = rules.effectiveRule(
                input.ruleId(), context.agentContext().hospitalId(), input.profileId());
        Map<String, Object> mapping = rules.fieldMapping(
                input.ruleId(), context.agentContext().hospitalId(), input.profileId());
        List<Map<String, Object>> layers = new ArrayList<>();

        ToolResult implementation = sqlTools.inspect(
                new IndicatorSqlTools.InspectInput(input.ruleId(), input.profileId()), context);
        Map<String, Object> structure = structureLayer(implementation);
        layers.add(structure);
        if (!Boolean.TRUE.equals(structure.get("ok"))) {
            return finish(input, context, layers);
        }

        layers.add(ruleLayer(rule));
        Map<String, Object> execution = executionLayer(input, context);
        layers.add(execution);
        if (!Boolean.TRUE.equals(execution.get("ok"))) {
            return finish(input, context, layers);
        }
        layers.add(dataLayer(rule, mapping));
        return finish(input, context, layers);
    }

    /**
     * 显式诊断在当前子任务内重新准备并执行审批 SQL，不依赖历史计算结果。
     * 生产环境的 SQL 工具会进入统一的抽取、双库概览和明细比较 Workflow。
     */
    private Map<String, Object> executionLayer(
            Input input, ToolExecutionContext context) {
        LocalDateTime[] period = parsePeriod(input.statPeriod());
        if (period == null) {
            return layer(3, "数据快照与双库诊断", false, List.of(
                    check("stat_period", "fail",
                            "诊断需要明确的统计开始时间和结束时间。",
                            "确认统计区间后重新诊断。")));
        }
        ToolResult prepared = sqlTools.prepare(
                new IndicatorSqlTools.PrepareInput(
                        input.ruleId(), period[0].toString(), period[1].toString(),
                        input.profileId()),
                context);
        if (!prepared.ok()) {
            return layer(3, "数据快照与双库诊断", false, List.of(
                    check("sql_prepare", "fail",
                            "审批 SQL 准备失败：" + prepared.summary(),
                            "根据错误码 " + prepared.code() + " 修正执行契约后重试。")));
        }
        ToolResult trial = sqlTools.trial(
                new IndicatorSqlTools.TrialInput(text(prepared.data().get("sql_id"))),
                context);
        if (!trial.ok()) {
            return layer(3, "数据快照与双库诊断", false, List.of(
                    check("dual_execution", "fail",
                            "数据准备或双库诊断失败：" + trial.summary(),
                            "根据错误码 " + trial.code() + " 检查抽取、DBHub 或明细契约。")));
        }

        List<Map<String, Object>> checks = new ArrayList<>();
        String extractionStatus = text(trial.data().get("extraction_status"));
        if ("SKIPPED_DISABLED".equals(extractionStatus)) {
            checks.add(check("source_extraction", "warn",
                    "抽取模式未启用，本轮未刷新真实库数据，结论基于现有快照。",
                    "启用并补齐抽取契约后可获得本轮新鲜快照。"));
        } else {
            checks.add(check("source_extraction", "pass",
                    "本轮已完成数据快照准备。", ""));
        }
        checks.add(check("dual_overview", "pass",
                "已完成业务库与真实库概览核对，状态："
                        + text(trial.data().get("comparison_status")) + "。", ""));
        Map<String, Object> dualDiagnosis =
                objectMap(trial.data().get("dual_difference_diagnosis"));
        String detailStatus = text(dualDiagnosis.get("status"));
        checks.add("completed".equals(detailStatus)
                ? check("dual_detail", "pass",
                        "已完成科室和患者明细比较。", "")
                : check("dual_detail", "warn",
                        "明细诊断未完整完成：" + text(dualDiagnosis.get("reason")),
                        "补齐并验证科室和患者明细比较契约。"));
        Map<String, Object> result = layer(3, "数据快照与双库诊断", true, checks);
        result.put("run_id", trial.data().get("run_id"));
        result.put("diagnosis_report_id", trial.data().get("diagnosis_report_id"));
        result.put("extraction_status", extractionStatus);
        result.put("data_freshness", trial.data().get("data_freshness"));
        result.put("comparison_status", trial.data().get("comparison_status"));
        result.put("business_result", trial.data().get("business_result"));
        result.put("real_result", trial.data().get("real_result"));
        result.put("dual_difference_diagnosis", dualDiagnosis);
        return result;
    }

    private ToolResult finish(Input input, ToolExecutionContext context, List<Map<String, Object>> layers) {
        boolean failed = layers.stream().anyMatch(layer -> !Boolean.TRUE.equals(layer.get("ok")));
        boolean warning = layers.stream().flatMap(layer -> listOfMaps(layer.get("checks")).stream())
                .anyMatch(check -> "warn".equals(check.get("status")));
        String status = failed ? "failed" : warning ? "warning" : "healthy";
        List<String> problems = layers.stream().flatMap(layer -> listOfMaps(layer.get("checks")).stream())
                .filter(check -> Set.of("warn", "fail").contains(text(check.get("status"))))
                .map(check -> text(check.get("message"))).filter(value -> !value.isBlank()).toList();
        List<String> suggestions = layers.stream().flatMap(layer -> listOfMaps(layer.get("checks")).stream())
                .filter(check -> Set.of("warn", "fail").contains(text(check.get("status"))))
                .map(check -> text(check.get("repair_suggest"))).filter(value -> !value.isBlank()).distinct().toList();
        String reportId = id("DR_");
        String summary = switch (status) {
            case "failed" -> "诊断未通过：" + String.join("；", problems);
            case "warning" -> "诊断完成但存在风险：" + String.join("；", problems);
            default -> "诊断完成，当前未发现结构、口径或数据质量异常。";
        };
        try {
            reports.save(reportId, context.agentContext().hospitalId(), input.ruleId(),
                    failed ? "诊断失败" : warning ? "诊断风险" : "诊断正常",
                    String.join("；", problems), String.join("；", suggestions), layers, status, input.statPeriod());
        } catch (RuntimeException exception) {
            return failure("error", "DIAGNOSIS_REPORT_SAVE_FAILED", "诊断已执行，但报告保存失败。", false);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rule_id", input.ruleId());
        data.put("profile_id", input.profileId());
        data.put("diagnose_status", status);
        data.put("report_id", reportId);
        data.put("summary", summary);
        data.put("user_summary", summary);
        data.put("layers", layers);
        Map<String, Object> execution = layers.stream()
                .filter(layer -> Integer.valueOf(3).equals(layer.get("layer")))
                .findFirst()
                .orElse(Map.of());
        copyIfPresent(execution, data, "run_id");
        copyIfPresent(execution, data, "diagnosis_report_id");
        copyIfPresent(execution, data, "extraction_status");
        copyIfPresent(execution, data, "data_freshness");
        copyIfPresent(execution, data, "comparison_status");
        List<String> confirmedFindings = layers.stream()
                .flatMap(layer -> listOfMaps(layer.get("checks")).stream())
                .filter(check -> "pass".equals(text(check.get("status"))))
                .map(check -> text(check.get("message")))
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
        if (!confirmedFindings.isEmpty()) {
            data.put("confirmed_findings", String.join("；", confirmedFindings));
        }
        if (!problems.isEmpty()) {
            data.put("evidence_limit", String.join("；", problems));
        }
        if (input.statPeriod() != null) data.put("stat_period", input.statPeriod());
        return ToolResult.success("INDICATOR_DIAGNOSED", "指标诊断已完成。", data);
    }

    private static void copyIfPresent(
            Map<String, Object> source, Map<String, Object> target, String key) {
        Object value = source.get(key);
        if (value != null && !text(value).isBlank()) {
            target.put(key, value);
        }
    }

    private static Map<String, Object> structureLayer(ToolResult implementation) {
        List<Map<String, Object>> checks = new ArrayList<>();
        addIssueChecks(checks, implementation.data(), "missing_mappings", "fail",
                "缺失字段映射：", "补齐并确认医院字段映射。");
        addIssueChecks(checks, implementation.data(), "unconfirmed_mappings", "fail",
                "字段映射尚未确认：", "确认字段映射后重新诊断。");
        addIssueChecks(checks, implementation.data(), "missing_columns", "fail",
                "最新元数据中缺少字段：", "同步元数据或修正映射。");
        addIssueChecks(checks, implementation.data(), "type_mismatches", "fail",
                "字段类型不兼容：", "修正字段映射或数据类型。");
        addIssueChecks(checks, implementation.data(), "missing_relations", "fail",
                "缺少跨表关联：", "确认关联字段与关联类型。");
        if (checks.isEmpty()) {
            checks.add(check("implementation", "pass", "字段映射、元数据和跨表关联已确认。", ""));
        }
        boolean ok = checks.stream().noneMatch(value -> "fail".equals(value.get("status")));
        return layer(1, "实施结构校验", ok, checks);
    }

    private static Map<String, Object> ruleLayer(Map<String, Object> rule) {
        List<Map<String, Object>> checks = new ArrayList<>();
        checks.add(text(rule.get("definition")).isBlank()
                ? check("definition", "fail", "指标定义缺失。", "先确认指标定义。")
                : check("definition", "pass", "指标定义已配置。", ""));
        checks.add(text(rule.get("formula")).isBlank()
                ? check("formula", "fail", "指标公式缺失。", "先确认指标公式。")
                : check("formula", "pass", "指标公式已配置。", ""));
        String sql = text(rule.get("standard_sql")).toUpperCase(Locale.ROOT);
        checks.add(sql.contains("CASE") || sql.contains("NULLIF")
                ? check("zero_guard", "pass", "SQL 已配置分母为零保护。", "")
                : check("zero_guard", "warn", "SQL 未明确配置分母为零保护。", "使用 CASE 或 NULLIF 保护零分母。"));
        List<?> overridden = rule.get("overridden_fields") instanceof List<?> list ? list : List.of();
        if (overridden.isEmpty()) {
            checks.add(check("caliber_override", "pass", "本院未覆盖国标口径。", ""));
        } else {
            checks.add(check("caliber_override", "warn",
                    "本院覆盖了国标口径字段：" + String.join("、", overridden.stream().map(String::valueOf).toList()) + "。",
                    "跨机构比较时应明确标注本院口径版本。"));
        }
        boolean ok = checks.stream().noneMatch(value -> "fail".equals(value.get("status")));
        return layer(2, "口径规则校验", ok, checks);
    }

    private Map<String, Object> dataLayer(Map<String, Object> rule, Map<String, Object> mapping) {
        List<Map<String, Object>> checks = new ArrayList<>();
        String mainTable = text(mapping.get("main_table"));
        String dialect = text(mapping.get("dialect"));
        String schema = text(mapping.get("schema"));
        if (mainTable.isBlank()) {
            return layer(4, "数据质量校验", true,
                    List.of(check("main_table", "warn",
                            "当前 Profile 未配置可独立检查的业务主表，字段空值率、重复记录和时间逻辑检查受限。",
                            "后续补齐该 Profile 的抽取与主表映射后再执行扩展数据质量检查。")));
        }
        if (!safeIdentifier(mainTable) || (!schema.isBlank() && !safeIdentifier(schema))) {
            return layer(4, "数据质量校验", false,
                    List.of(check("main_table", "fail", "主表标识无效。", "修正医院字段映射。")));
        }
        String qualified = qualify(schema, mainTable, dialect);
        long total;
        try {
            List<Map<String, Object>> rows = businessQuery.execute(
                    "SELECT " + ("sqlserver".equals(dialect) ? "COUNT_BIG" : "COUNT") + "(*) AS total FROM " + qualified);
            total = longValue(rows.isEmpty() ? null : value(rows.get(0), "total"));
            checks.add(total < 10
                    ? check("table_rows", "warn", "业务主表样本量较小：" + total + " 行。", "确认是否为测试库或数据尚未同步。")
                    : check("table_rows", "pass", "业务主表可访问，共 " + total + " 行。", ""));
        } catch (RuntimeException exception) {
            return layer(4, "数据质量校验", false,
                    List.of(check("main_table_access", "fail", "无法通过 DBHub 访问业务主表。", "检查 DBHub、只读权限和数据源配置。")));
        }

        Map<String, Object> contractFields = objectMap(objectMap(rule.get("field_contract")).get("business_fields"));
        for (Map.Entry<String, Object> entry : objectMap(mapping.get("fields")).entrySet()) {
            String[] parts = text(entry.getValue()).split("\\.");
            if (parts.length < 2 || !mainTable.equals(parts[parts.length - 2])) continue;
            String column = parts[parts.length - 1];
            if (!safeIdentifier(column)) continue;
            boolean required = Boolean.TRUE.equals(objectMap(contractFields.get(entry.getKey())).get("required"));
            try {
                String quoted = quote(column, dialect);
                List<Map<String, Object>> rows = businessQuery.execute(
                        "SELECT " + ("sqlserver".equals(dialect) ? "COUNT_BIG" : "COUNT") + "(*) AS total, "
                                + "SUM(CASE WHEN " + quoted + " IS NULL THEN 1 ELSE 0 END) AS nulls FROM " + qualified);
                Map<String, Object> row = rows.isEmpty() ? Map.of() : rows.get(0);
                long rowTotal = longValue(value(row, "total"));
                long nulls = longValue(value(row, "nulls"));
                double rate = rowTotal == 0 ? 0 : nulls * 1.0 / rowTotal;
                boolean risky = (required && rate >= 0.5) || rate > 0.3;
                checks.add(risky
                        ? check("null_rate." + entry.getKey(), "warn",
                                "字段 " + column + " 空值率较高：" + nulls + "/" + rowTotal + "。",
                                "核对源数据质量与字段映射。")
                        : check("null_rate." + entry.getKey(), "pass",
                                "字段 " + column + " 空值率可接受：" + nulls + "/" + rowTotal + "。", ""));
            } catch (RuntimeException exception) {
                checks.add(check("field." + entry.getKey(), required ? "fail" : "warn",
                        "字段 " + column + " 的数据质量检查失败。", "确认字段存在且 DBHub 账号具有只读权限。"));
            }
        }
        boolean ok = checks.stream().noneMatch(value -> "fail".equals(value.get("status")));
        return layer(4, "数据质量校验", ok, checks);
    }

    private static LocalDateTime[] parsePeriod(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String[] parts = value.strip().split("\\s*(?:~|至|到)\\s*", 2);
        if (parts.length != 2) {
            return null;
        }
        try {
            LocalDateTime start = LocalDateTime.parse(parts[0]);
            LocalDateTime end = LocalDateTime.parse(parts[1]);
            return start.isBefore(end) ? new LocalDateTime[] { start, end } : null;
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private static void addIssueChecks(
            List<Map<String, Object>> checks,
            Map<String, Object> data,
            String key,
            String status,
            String prefix,
            String suggestion) {
        Object value = data.get(key);
        if (!(value instanceof List<?> list)) return;
        for (Object item : list) {
            checks.add(check(key, status, prefix + item, suggestion));
        }
    }

    private static Map<String, Object> layer(
            int number, String name, boolean ok, List<Map<String, Object>> checks) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("layer", number);
        result.put("layer_name", name);
        result.put("ok", ok);
        result.put("checks", checks);
        return result;
    }

    private static Map<String, Object> check(
            String name, String status, String message, String repairSuggest) {
        return Map.of(
                "name", name,
                "status", status,
                "message", message,
                "repair_suggest", repairSuggest);
    }

    private static String qualify(String schema, String table, String dialect) {
        return schema.isBlank() ? quote(table, dialect) : quote(schema, dialect) + "." + quote(table, dialect);
    }

    private static String quote(String value, String dialect) {
        return "sqlserver".equals(dialect) ? "[" + value + "]" : "`" + value + "`";
    }

    private static boolean safeIdentifier(String value) {
        return value != null && value.matches("[A-Za-z_][A-Za-z0-9_]*");
    }

    private static Object value(Map<String, Object> row, String key) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (key.equalsIgnoreCase(entry.getKey())) return entry.getValue();
        }
        return null;
    }

    private static long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null || value.toString().isBlank()) return 0;
        return Double.valueOf(value.toString()).longValue();
    }

    private static List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        list.forEach(item -> result.add(objectMap(item)));
        return result;
    }

    private static Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return new LinkedHashMap<>();
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static ToolResult failure(String status, String code, String summary, boolean retryable) {
        return new ToolResult(false, status, code, summary, Map.of(), retryable, false, List.of());
    }

    private static String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String first(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return "";
    }

    public record Input(
            String ruleId, String issueDescription, String statPeriod, String profileId) {
        public Input {
            ruleId = ruleId == null ? "" : ruleId.strip();
            issueDescription = issueDescription == null ? "" : issueDescription.strip();
            statPeriod = statPeriod == null || statPeriod.isBlank() ? null : statPeriod.strip();
            profileId = profileId == null || profileId.isBlank() ? null : profileId.strip();
            if (ruleId.isEmpty() || issueDescription.isEmpty() || issueDescription.length() > 1000) {
                throw new IllegalArgumentException("诊断参数无效");
            }
        }

        public Input(String ruleId, String issueDescription, String statPeriod) {
            this(ruleId, issueDescription, statPeriod, null);
        }
    }
}
