package com.hospital.wikiagent.sqlserver;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 真实库唯一允许清理和写入的数据库对象上限。
 *
 * <p>知识发布包只能从此上限中选择表，不能扩大权限。所有标识符在拼接 SQL 前都必须
 * 经过本类型校验，从而拒绝跨库名称、任意 Schema 和 SQL 注入片段。</p>
 */
public final class RealDatabaseSafetyPolicy {
    public static final String DATABASE = "winex_aima";
    public static final String SCHEMA = "dbo";

    public static final List<String> TABLES = List.of(
            "BUSINESS_UNIT_X_BU_TYPE",
            "CLIBASIC_SURGERY",
            "INPATIENT_ENCOUNTER",
            "INPAT_TRANSFER",
            "INP_CLI_ORDER",
            "INP_SURGICAL_ANESTHESIA_PLAN",
            "MRAS_BUSINESS_ANTI",
            "MRAS_BUSINESS_BLOOD_AUDIT",
            "MRAS_BUSINESS_CONSULTATION",
            "MRAS_BUSINESS_CRITICAL_RPT",
            "MRAS_BUSINESS_DEATH",
            "MRAS_BUSINESS_DIFFI_EMR",
            "MRAS_BUSINESS_DIFFI_EMR_SECOND",
            "MRAS_BUSINESS_FIRSTVISIT",
            "MRAS_BUSINESS_GRADED_CARE",
            "MRAS_BUSINESS_OP_DISC",
            "MRAS_BUSINESS_PATRESCUE",
            "MRAS_BUSINESS_SHIFTHANDOVER",
            "MRAS_BUSINESS_SURGERY",
            "MRAS_BUSINESS_SUR_GRADE",
            "MRAS_BUSINESS_WARDROUND",
            "MRAS_INDEX_SURGREC",
            "MRAS_MEDTECH_PRO",
            "MRAS_MEDTECH_PROC",
            "MRAS_ORGANIZATION",
            "MRAS_PATIENT_EVENT",
            "MRAS_TARGET_DEFINITION",
            "ORGANIZATION");

    private static final Set<String> TABLE_SET = TABLES.stream()
            .map(value -> value.toUpperCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());

    private RealDatabaseSafetyPolicy() {
    }

    public static String requireAllowedTable(String value) {
        String table = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
        if (!table.matches("[A-Z][A-Z0-9_]*") || !TABLE_SET.contains(table)) {
            throw new IllegalArgumentException("REAL_DB_TABLE_NOT_ALLOWED: " + value);
        }
        return table;
    }

    public static String qualified(String value) {
        return "[dbo].[" + requireAllowedTable(value) + "]";
    }
}
