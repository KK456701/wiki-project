package com.hospital.wikiagent.implementation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class DraftSqlPlanRendererTest {
    private final DraftSqlPlanRenderer renderer = new DraftSqlPlanRenderer();

    @Test
    void compilesParameterizedSqlServerAggregateFromConfirmedMappings() {
        DraftSqlPlanRenderer.RenderedSql result = renderer.render(plan(), mappings());

        assertThat(result.sql()).contains("DATEDIFF(MINUTE, request_time, arrive_time)")
                .contains("hospital_id = :hospital_id")
                .contains("AS numerator_count").contains("AS denominator_count")
                .doesNotContain("急会诊");
        assertThat(result.params()).containsValue("急会诊").containsValue(20);
        assertThat(result.dialect()).isEqualTo("sqlserver");
    }

    @Test
    void rejectsUnconfirmedOrUnsafeIdentifiers() {
        Map<String, Object> unsafe = new java.util.LinkedHashMap<>(mappings());
        unsafe.put("consult_id", Map.of("table_name", "consult_record", "column_name", "id;DROP"));
        assertThatThrownBy(() -> renderer.render(plan(), unsafe))
                .isInstanceOf(ImplementationException.class).hasMessageContaining("非法标识符");
    }

    private static Map<String, Object> plan() {
        return Map.of(
                "main_table", "consult_record", "metric_type", "ratio",
                "subject_field", "consult_id", "time_field", "request_time",
                "hospital_field", "hospital_id",
                "denominator_conditions", List.of(Map.of(
                        "field", "consult_type", "operator", "eq", "value", "急会诊")),
                "numerator_conditions", List.of(
                        Map.of("field", "arrive_time", "operator", "minutes_between_lte",
                                "compare_field", "request_time", "value", 20)));
    }

    private static Map<String, Object> mappings() {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        for (String field : List.of("hospital_id", "consult_id", "request_time", "arrive_time", "consult_type")) {
            result.put(field, Map.of("table_name", "consult_record", "column_name", field));
        }
        return result;
    }
}
