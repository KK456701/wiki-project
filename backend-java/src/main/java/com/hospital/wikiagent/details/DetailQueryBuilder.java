package com.hospital.wikiagent.details;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.hospital.wikiagent.details.DetailContracts.DetailColumn;
import com.hospital.wikiagent.details.DetailContracts.DetailQuery;
import com.hospital.wikiagent.details.DetailContracts.RunContext;

/**
 * 按受控规则构建 {@code DetailQueryBuilder} 对应的业务对象。
 *
 * <p>输出由结构化输入确定性生成，禁止拼接未校验的标识符或执行任意 SQL。生成结果必须保留来源对象和版本，便于审计与复现。</p>
 */
@Component
public class DetailQueryBuilder {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    public DetailQuery build(RunContext context, int rowLimit) {
        if (rowLimit < 1 || rowLimit > 20_001) {
            throw new IllegalArgumentException("明细查询行数限制必须在1至20,001之间");
        }
        return switch (context.queryProfile()) {
            case "urgent_consult_sqlserver" -> urgentConsult(context, rowLimit);
            case "inpatient_transfer_48h_sqlserver" -> inpatientTransfer(context, rowLimit);
            default -> throw new IllegalArgumentException("当前指标尚未迁移可核对的 Java 明细模板");
        };
    }

    private DetailQuery urgentConsult(RunContext context, int rowLimit) {
        requireParameters(context.parameters(), Set.of(
                "hospital_soid", "urgent_level_code", "arrive_minutes_threshold",
                "start_time", "end_time"));
        List<DetailColumn> columns = columns(context);
        Map<String, String> expressions = Map.of(
                "consult_id", "base.consult_id",
                "patient_id", "base.patient_id",
                "dept_id", "base.dept_id",
                "consult_type", "N'急会诊'",
                "request_time", "base.request_time",
                "arrive_time", "base.arrive_time",
                "arrive_minutes", "DATEDIFF(MINUTE, base.request_time, base.arrive_time)");
        List<String> selected = selectColumns(columns, expressions);
        selected.add("  CASE WHEN DATEDIFF(MINUTE, base.request_time, base.arrive_time) "
                + "BETWEEN 0 AND :arrive_minutes_threshold THEN 1 ELSE 0 END "
                + "AS [__meets_numerator]");
        selected.add("  1 AS [__evidence_row_count]");
        String schema = schema(context);
        String sql = "SELECT TOP " + rowLimit + "\n"
                + String.join(",\n", selected)
                + "\nFROM (\n"
                + "  SELECT\n"
                + "    apply_record.INP_CONSULT_APPLY_ID AS consult_id,\n"
                + "    apply_record.ADMISSION_NUMBER AS patient_id,\n"
                + "    apply_record.DEPT_ID AS dept_id,\n"
                + "    apply_record.APPLY_CONSULT_SENT_AT AS request_time,\n"
                + "    MIN(CASE WHEN invitation.IS_DEL = 0\n"
                + "                  AND invitation.SIGNED_AT >= apply_record.APPLY_CONSULT_SENT_AT\n"
                + "             THEN invitation.SIGNED_AT END) AS arrive_time\n"
                + "  FROM " + schema + ".INPATIENT_CONSULT_APPLY AS apply_record\n"
                + "  LEFT JOIN " + schema + ".INP_CONSULT_INVITATION AS invitation\n"
                + "    ON invitation.INP_CONSULT_APPLY_ID = apply_record.INP_CONSULT_APPLY_ID\n"
                + "   AND invitation.HOSPITAL_SOID = apply_record.HOSPITAL_SOID\n"
                + "  WHERE apply_record.HOSPITAL_SOID = :hospital_soid\n"
                + "    AND apply_record.CONSULT_LEVEL_CODE = :urgent_level_code\n"
                + "    AND apply_record.IS_DEL = 0\n"
                + "    AND apply_record.CONSULT_CANCEL_AT IS NULL\n"
                + "    AND apply_record.APPLY_CONSULT_SENT_AT >= :start_time\n"
                + "    AND apply_record.APPLY_CONSULT_SENT_AT < :end_time\n"
                + "  GROUP BY apply_record.INP_CONSULT_APPLY_ID,\n"
                + "           apply_record.ADMISSION_NUMBER, apply_record.DEPT_ID,\n"
                + "           apply_record.APPLY_CONSULT_SENT_AT\n"
                + ") AS base\n"
                + "ORDER BY base.request_time, base.consult_id";
        return new DetailQuery(sql, context.parameters(), columns);
    }

    private DetailQuery inpatientTransfer(RunContext context, int rowLimit) {
        requireParameters(context.parameters(), Set.of(
                "hospital_soid", "excluded_inpatient_business_code",
                "transfer_department_code", "transfer_ward_code", "icu_org_ids_csv",
                "transfer_minutes_threshold", "start_time", "end_time"));
        List<DetailColumn> columns = columns(context);
        Map<String, String> expressions = Map.of(
                "admission_id", "base.admission_id",
                "admit_time", "base.admit_time",
                "transfer_time", "base.transfer_time",
                "from_dept_id", "base.from_dept_id",
                "from_ward_id", "base.from_ward_id",
                "to_dept_id", "base.to_dept_id",
                "to_ward_id", "base.to_ward_id",
                "transfer_minutes", "DATEDIFF(MINUTE, base.admit_time, base.transfer_time)");
        List<String> selected = selectColumns(columns, expressions);
        selected.add("  CASE WHEN DATEDIFF(MINUTE, base.admit_time, base.transfer_time) "
                + "BETWEEN 0 AND :transfer_minutes_threshold THEN 1 ELSE 0 END "
                + "AS [__meets_numerator]");
        selected.add("  1 AS [__evidence_row_count]");
        String schema = schema(context);
        String admitColumn = profileColumn(context, "admit_time");
        String periodColumn = profileColumn(context, "period_time");
        String sql = "WITH eligible_encounter AS (\n"
                + "  SELECT encounter.ENCOUNTER_ID AS admission_id,\n"
                + "         encounter." + admitColumn + " AS admit_time\n"
                + "  FROM " + schema + ".INPATIENT_ENCOUNTER AS encounter\n"
                + "  WHERE encounter.HOSPITAL_SOID = :hospital_soid\n"
                + "    AND encounter.IS_DEL = 0\n"
                + "    AND encounter.INPAT_ENC_BIZ_TYPE_CODE <> :excluded_inpatient_business_code\n"
                + "    AND encounter." + periodColumn + " >= :start_time\n"
                + "    AND encounter." + periodColumn + " < :end_time\n"
                + "),\n"
                + "transfer_candidate AS (\n"
                + transferBranch(schema, ":transfer_department_code", false)
                + "  UNION ALL\n"
                + transferBranch(schema, ":transfer_ward_code", true)
                + "),\n"
                + "valid_transfer AS (\n"
                + "  SELECT candidate.*,\n"
                + "         ROW_NUMBER() OVER (PARTITION BY candidate.admission_id "
                + "ORDER BY candidate.transfer_time, candidate.transfer_id) AS event_order\n"
                + "  FROM transfer_candidate AS candidate\n"
                + "  WHERE (\n"
                + icuCondition("candidate.from_dept_id") + "\n    + "
                + icuCondition("candidate.from_ward_id") + "\n    + "
                + icuCondition("candidate.to_dept_id") + "\n    + "
                + icuCondition("candidate.to_ward_id") + "\n  ) = 0\n"
                + "),\n"
                + "base AS (\n"
                + "  SELECT encounter.admission_id, encounter.admit_time,\n"
                + "         transfer.transfer_time, transfer.from_dept_id,\n"
                + "         transfer.from_ward_id, transfer.to_dept_id, transfer.to_ward_id\n"
                + "  FROM eligible_encounter AS encounter\n"
                + "  LEFT JOIN valid_transfer AS transfer\n"
                + "    ON transfer.admission_id = encounter.admission_id\n"
                + "   AND transfer.event_order = 1\n"
                + ")\n"
                + "SELECT TOP " + rowLimit + "\n"
                + String.join(",\n", selected)
                + "\nFROM base\nORDER BY base.admit_time, base.admission_id";
        return new DetailQuery(sql, context.parameters(), columns);
    }

    private static String transferBranch(String schema, String typeParameter, boolean wardTransfer) {
        return "  SELECT transfer.INPAT_TRANSFER_ID AS transfer_id,\n"
                + "         transfer.ENCOUNTER_ID AS admission_id,\n"
                + "         transfer.INPAT_TRANSFER_AT AS transfer_time,\n"
                + "         transfer.ORIGIN_DEPT_ID AS from_dept_id,\n"
                + "         transfer.ORIGIN_WARD_ID AS from_ward_id,\n"
                + "         transfer.DESTINATION_DEPT_ID AS to_dept_id,\n"
                + "         transfer.DESTINATION_WARD_ID AS to_ward_id\n"
                + "  FROM " + schema + ".INPAT_TRANSFER AS transfer\n"
                + "  WHERE transfer.HOSPITAL_SOID = :hospital_soid\n"
                + "    AND transfer.IS_DEL = 0\n"
                + "    AND transfer.INPAT_TRANSFER_TYPE_CODE = " + typeParameter + "\n"
                + (wardTransfer
                        ? "    AND transfer.ORIGIN_DEPT_ID <> transfer.DESTINATION_DEPT_ID\n"
                        : "");
    }

    private static String icuCondition(String field) {
        return "    CASE WHEN CHARINDEX(',' + CONVERT(varchar(30), " + field
                + ") + ',', ',' + :icu_org_ids_csv + ',') > 0 THEN 1 ELSE 0 END";
    }

    private static List<String> selectColumns(
            List<DetailColumn> columns,
            Map<String, String> expressions) {
        List<String> selected = new ArrayList<>();
        for (DetailColumn column : columns) {
            String expression = expressions.get(column.field());
            if (expression == null) {
                throw new IllegalArgumentException("明细模板尚未支持字段：" + column.field());
            }
            selected.add("  " + expression + " AS [" + identifier(column.field()) + "]");
        }
        return selected;
    }

    private static List<DetailColumn> columns(RunContext context) {
        Object raw = context.calculationDefinition().get("detail_fields");
        if (!(raw instanceof List<?> values) || values.isEmpty()) {
            throw new IllegalArgumentException("当前指标尚未配置可核对的明细字段");
        }
        Map<String, Object> labels = objectMap(context.fieldMapping().get("field_labels"));
        List<DetailColumn> result = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> item)) {
                throw new IllegalArgumentException("明细字段配置格式无效");
            }
            String field = text(item.get("field"));
            String label = text(labels.get(field));
            if (label.isBlank()) {
                label = text(item.get("label"));
            }
            String sensitivity = text(item.get("sensitivity"));
            result.add(new DetailColumn(field, label, sensitivity));
        }
        return List.copyOf(result);
    }

    private static String profileColumn(RunContext context, String role) {
        Map<String, Object> fields = objectMap(context.fieldMapping().get("fields"));
        Map<String, Object> overrides = objectMap(context.executionContext().get("overrides"));
        Map<String, Object> resolvedFields = objectMap(context.executionContext().get("resolved_fields"));
        if (overrides.containsKey("period_time_field")) {
            fields.put("period_time", requiredResolvedField(resolvedFields, "period_time_field"));
        }
        if (overrides.containsKey("elapsed_time_start")) {
            fields.put("admit_time", requiredResolvedField(resolvedFields, "elapsed_time_start"));
        }
        String mapped = text(fields.get(role));
        if (mapped.isBlank() && "period_time".equals(role)) {
            mapped = text(fields.get("admit_time"));
        }
        String[] parts = mapped.split("\\.");
        if (parts.length != 2 || !"INPATIENT_ENCOUNTER".equals(parts[0])) {
            throw new IllegalArgumentException("入院转科明细缺少已确认字段角色：" + role);
        }
        return identifier(parts[1]);
    }

    private static String requiredResolvedField(Map<String, Object> resolvedFields, String role) {
        String value = text(resolvedFields.get(role));
        String[] parts = value.split("\\.");
        if (parts.length != 2) {
            throw new IllegalArgumentException("会话口径缺少已确认字段：" + role);
        }
        identifier(parts[0]);
        identifier(parts[1]);
        return value;
    }

    private static String schema(RunContext context) {
        return identifier(text(context.fieldMapping().getOrDefault("schema", "WINDBA")));
    }

    private static void requireParameters(Map<String, Object> parameters, Set<String> required) {
        List<String> missing = required.stream()
                .filter(key -> !parameters.containsKey(key) || parameters.get(key) == null)
                .sorted()
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("明细查询缺少口径参数：" + String.join("、", missing));
        }
    }

    private static String identifier(String value) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("明细字段映射包含非法标识符");
        }
        return value;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private static Map<String, Object> objectMap(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
        }
        return result;
    }
}
