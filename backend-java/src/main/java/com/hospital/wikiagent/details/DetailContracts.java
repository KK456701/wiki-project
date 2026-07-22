package com.hospital.wikiagent.details;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实现 {@code DetailContracts} 对应的领域职责。
 */
public final class DetailContracts {
    private DetailContracts() {
    }

    public record DetailColumn(String field, String label, String sensitivity) {
        public DetailColumn {
            field = required(field, "明细字段不能为空");
            label = required(label, "明细字段名称不能为空");
            sensitivity = sensitivity == null || sensitivity.isBlank() ? "none" : sensitivity.strip();
        }
    }

    public record DetailQuery(String sql, Map<String, Object> parameters, List<DetailColumn> columns) {
        public DetailQuery {
            sql = required(sql, "明细 SQL 不能为空");
            parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
            columns = List.copyOf(columns);
        }
    }

    public record SnapshotSummary(
            String snapshotId,
            String runId,
            String hospitalId,
            String ruleId,
            String ruleName,
            String effectiveLevel,
            String nationalVersion,
            Integer hospitalVersion,
            String statStart,
            String statEnd,
            int denominatorCount,
            int numeratorCount,
            int unmatchedCount,
            List<DetailColumn> columns,
            Instant createdAt,
            Instant expiresAt,
            boolean reused,
            String sourceDatabase,
            List<String> sourceTables) {
        public SnapshotSummary {
            columns = List.copyOf(columns);
            sourceTables = List.copyOf(sourceTables);
        }
    }

    public record DetailPage(
            String snapshotId,
            String runId,
            String group,
            int page,
            int pageSize,
            int total,
            List<Map<String, Object>> items) {
        public DetailPage {
            items = List.copyOf(items);
        }
    }

    public record ExportSummary(
            String exportId,
            String runId,
            String hospitalId,
            String ruleId,
            String fileName,
            int rowCount,
            String status,
            Instant createdAt,
            Instant expiresAt,
            int downloadCount) {
    }

    public record RunContext(
            String runId,
            String sqlId,
            String hospitalId,
            String ruleId,
            String ruleName,
            String effectiveLevel,
            String nationalVersion,
            Integer hospitalVersion,
            String statStart,
            String statEnd,
            String dbSource,
            String mainTable,
            String dialect,
            String queryProfile,
            Map<String, Object> calculationDefinition,
            Map<String, Object> fieldMapping,
            Map<String, Object> parameters,
            Map<String, Object> executionContext,
            Long aggregateNumerator,
            Long aggregateDenominator) {
        public RunContext {
            calculationDefinition = immutableMap(calculationDefinition);
            fieldMapping = immutableMap(fieldMapping);
            parameters = immutableMap(parameters);
            executionContext = immutableMap(executionContext);
        }
    }

    public record SnapshotPayload(
            SnapshotSummary summary,
            List<Map<String, Object>> rows) {
        public SnapshotPayload {
            rows = List.copyOf(rows);
        }
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }

    private static Map<String, Object> immutableMap(Map<String, Object> value) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(value == null ? Map.of() : value));
    }
}
