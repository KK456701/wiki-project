package com.hospital.wikiagent.details;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.hospital.wikiagent.details.DetailContracts.RunContext;

class DetailQueryBuilderTest {
    private final DetailQueryBuilder builder = new DetailQueryBuilder();

    @Test
    void buildsUrgentConsultDetailOnlyFromFixedProfile() {
        var query = builder.build(context(
                "urgent_consult_sqlserver",
                List.of(
                        field("consult_id", "会诊编号", "none"),
                        field("patient_id", "患者标识", "patient_id"),
                        field("arrive_minutes", "到位耗时", "none")),
                Map.of(
                        "hospital_soid", 991827,
                        "urgent_level_code", 977578,
                        "arrive_minutes_threshold", 20,
                        "start_time", "2026-01-01 00:00:00",
                        "end_time", "2026-04-01 00:00:00")), 101);

        assertThat(query.sql())
                .startsWith("SELECT TOP 101")
                .contains("WINDBA.INPATIENT_CONSULT_APPLY", "[__meets_numerator]")
                .doesNotContain("INSERT", "UPDATE", "DELETE");
        assertThat(query.columns()).extracting("field")
                .containsExactly("consult_id", "patient_id", "arrive_minutes");
    }

    @Test
    void buildsTransferDetailWithConfirmedTimeColumns() {
        var query = builder.build(context(
                "inpatient_transfer_48h_sqlserver",
                List.of(
                        field("admission_id", "入院流水号", "patient_id"),
                        field("admit_time", "入院时间", "none"),
                        field("transfer_time", "转科时间", "none"),
                        field("transfer_minutes", "转科耗时", "none")),
                Map.ofEntries(
                        Map.entry("hospital_soid", 991827),
                        Map.entry("excluded_inpatient_business_code", 1),
                        Map.entry("transfer_department_code", 2),
                        Map.entry("transfer_ward_code", 3),
                        Map.entry("icu_org_ids_csv", "10,11"),
                        Map.entry("transfer_minutes_threshold", 2880),
                        Map.entry("start_time", "2026-01-01 00:00:00"),
                        Map.entry("end_time", "2026-04-01 00:00:00"))), 20001);

        assertThat(query.sql())
                .contains("WITH eligible_encounter", "encounter.ADMITTED_AT", "SELECT TOP 20001")
                .contains("CHARINDEX", "ROW_NUMBER() OVER")
                .doesNotContain("{{", "{%");
    }

    @Test
    void rejectsMissingProfileParameterBeforeBuildingSql() {
        assertThatThrownBy(() -> builder.build(context(
                "urgent_consult_sqlserver",
                List.of(field("consult_id", "会诊编号", "none")),
                Map.of("hospital_soid", 1)), 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("明细查询缺少口径参数");
    }

    private static RunContext context(
            String profile,
            List<Map<String, Object>> columns,
            Map<String, Object> parameters) {
        return new RunContext(
                "RUN_test", "SQL_test", "hospital_001", "MQSI2025_001", "测试指标",
                "hospital", "2025", 4, "2026-01-01 00:00:00", "2026-04-01 00:00:00",
                "business", "urgent_consult_sqlserver".equals(profile)
                        ? "INPATIENT_CONSULT_APPLY" : "INPATIENT_ENCOUNTER",
                "sqlserver", profile,
                Map.of("detail_fields", columns),
                Map.of(
                        "schema", "WINDBA",
                        "fields", Map.of(
                                "admit_time", "INPATIENT_ENCOUNTER.ADMITTED_AT",
                                "period_time", "INPATIENT_ENCOUNTER.ADMITTED_AT")),
                parameters, Map.of(), 1L, 2L);
    }

    private static Map<String, Object> field(String field, String label, String sensitivity) {
        return Map.of("field", field, "label", label, "sensitivity", sensitivity);
    }
}
