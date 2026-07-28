package com.hospital.wikiagent.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 业务患者维度表枚举：定义抽取时需要按 ENCOUNTER_ID 过滤的患者级表及其关联条件。
 *
 * <p>每个枚举实例包含表名和对应的 WHERE 条件模板（包含 {@code :encounterIds} 占位符），
 * 由 {@link com.hospital.wikiagent.service.SyncDataService} 在同步 bizDataList 时调用。</p>
 */
@Getter
@AllArgsConstructor
public enum BizTableEnum {

    INP_CLI_ORDER("INP_CLI_ORDER", ""),

    INP_SURGICAL_ANESTHESIA_PLAN("INP_SURGICAL_ANESTHESIA_PLAN", "CLI_ORDER_ITEM_ID in (select CLI_ORDER_ITEM_ID from INP_SURGICAL_PLAN WHERE ENCOUNTER_ID in (:encounterIds))"),

    INPAT_TRANSFER("INPAT_TRANSFER", ""),

    INPATIENT_ENCOUNTER("INPATIENT_ENCOUNTER", ""),

    MRAS_INDEX_SURGREC("MRAS_INDEX_SURGREC", "MRAS_INDEX_ENCOUNTER_ID in (:encounterIds)"),

    ;

    /**
     * 类型码
     */
    private String table;

    /**
     * 描述
     */
    private String condition;

    /**
     * 获取所有表名列表
     *
     * @return 表名列表
     */
    public static List<String> getTableList() {
        return Arrays.stream(values())
                .map(BizTableEnum::getTable)
                .collect(Collectors.toList());
    }

    /**
     * 根据表名获取条件
     *
     * @param table 表名
     * @return 条件，未找到返回 null
     */
    public static String getConditionByTable(String table) {
        if (table == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(e -> e.getTable().equalsIgnoreCase(table))
                .findFirst()
                .map(BizTableEnum::getCondition)
                .orElse(null);
    }


}
