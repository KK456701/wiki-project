package com.hospital.wikiagent.agent.mras;

import java.util.List;
import java.util.Map;

/**
 * 知识库实体页的结构化解析结果，承载一个指标维度的全部元数据和四段 SQL。
 *
 * <p>职责边界：纯数据载体（不可变 record），不含任何解析或渲染逻辑；
 * 由 EntityPageParser 在启动时一次性构建并缓存。</p>
 *
 * @param code             指标编码，如 HXZD-001-001
 * @param name             指标名称，如 患者入院48小时内转科的比例
 * @param dimension        时间维度后缀，如 入区时间（可为空）
 * @param variantCode      扩展编码，如 HXZD-003-003_001（无变体时等于 code）
 * @param variantLabel     方案类型，如 "推荐方案（公版）" / "变体方案"
 * @param definition       指标定义文本
 * @param formula          计算公式（分子/分母/公式）
 * @param caliber          统计口径详细说明
 * @param dataSource       数据来源表格文本
 * @param monitorParams    监测参数表格文本
 * @param significance     指标意义（从 concepts/ 补充，可为空）
 * @param unit             计量单位（从 concepts/ 补充，可为空）
 * @param system           所属制度名称（如 首诊负责制度）
 * @param category         四维分类（时限类/逻辑判定类等）
 * @param sourceTableSql   源表 ETL 抽取 SQL（可能含多段，用换行拼接）
 * @param overviewSql      目标表-概览 SQL
 * @param deptStatSql      目标表-科室统计 SQL
 * @param patientDetailSql 目标表-患者明细 SQL
 * @param eventNo          事件编码（如 CORE_FDR），用于 SyncDataService 抽取时标识事件类型
 * @param targetTable      中间表名（如 MRAS_BUSINESS_FIRSTVISIT），抽取数据的目标表
 * @param bizTables        业务表（影响数据）列表，抽取时需同步的依赖表
 * @param extendedEvents   关联拓展事件列表（eventNo → sqlScript），部分指标需要额外抽取的患者事件表
 */
public record EntityPageData(
        String code,
        String name,
        String dimension,
        String variantCode,
        String variantLabel,
        String definition,
        String formula,
        String caliber,
        String dataSource,
        String monitorParams,
        String significance,
        String unit,
        String system,
        String category,
        String sourceTableSql,
        String overviewSql,
        String deptStatSql,
        String patientDetailSql,
        String eventNo,
        String targetTable,
        List<String> bizTables,
        List<Map.Entry<String, String>> extendedEvents
) {
    /**
     * 是否具备可执行的概览 SQL。
     */
    public boolean hasOverviewSql() {
        return overviewSql != null && !overviewSql.isBlank();
    }

    /**
     * 是否为主方案（_001 或无变体编号）。
     */
    public boolean isPrimary() {
        return variantCode == null || variantCode.equals(code)
                || variantCode.endsWith("_001");
    }

    /**
     * 是否具备抽取所需的源表 SQL 和目标表名。
     */
    public boolean canExtract() {
        return sourceTableSql != null && !sourceTableSql.isBlank()
                && targetTable != null && !targetTable.isBlank();
    }
}
