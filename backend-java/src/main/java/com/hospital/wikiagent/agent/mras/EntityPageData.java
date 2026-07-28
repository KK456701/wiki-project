package com.hospital.wikiagent.agent.mras;

/**
 * 领导知识库实体页的结构化解析结果，承载一个指标维度的全部元数据和四段 SQL。
 *
 * <p>职责边界：纯数据载体（不可变 record），不含任何解析或渲染逻辑；
 * 由 EntityPageParser 在启动时一次性构建并缓存。</p>
 *
 * @param code             指标编码，如 HXZD-001-001
 * @param name             指标名称，如 患者入院48小时内转科的比例
 * @param dimension        时间维度后缀，如 入区时间（可为空）
 * @param definition       指标定义文本
 * @param formula          计算公式（分子/分母/公式）
 * @param caliber          统计口径详细说明
 * @param dataSource       数据来源表格文本
 * @param monitorParams    监测参数表格文本
 * @param sourceTableSql   源表 ETL 抽取 SQL（可能含多段，用换行拼接）
 * @param overviewSql      目标表-概览 SQL
 * @param deptStatSql      目标表-科室统计 SQL
 * @param patientDetailSql 目标表-患者明细 SQL
 */
public record EntityPageData(
        String code,
        String name,
        String dimension,
        String definition,
        String formula,
        String caliber,
        String dataSource,
        String monitorParams,
        String sourceTableSql,
        String overviewSql,
        String deptStatSql,
        String patientDetailSql
) {
    /**
     * 是否具备可执行的概览 SQL。
     */
    public boolean hasOverviewSql() {
        return overviewSql != null && !overviewSql.isBlank();
    }
}
