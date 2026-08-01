package com.hospital.wikiagent.agent.mras;

import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.initialization.MrasSqlLineageAnalyzer;

/**
 * 指标口径数据链路的唯一分类入口。初始化校验和展示层必须共用该判断，
 * 避免“页面判为真实库直算、校验器却要求中间表”的规则漂移。
 *
 * <p>本组件只读取知识库实体中已经登记的概览 SQL、源表 SQL、目标表和拓展事件，
 * 不访问数据库，也不会推测缺失链路；证据不足时返回 {@link FlowType#INCOMPLETE}，
 * 由初始化编排器显式跳过，由数据链路页面说明配置缺口。</p>
 */
@Component
public class IndicatorDataFlowTypeResolver {

    private static final String PATIENT_EVENT = "MRAS_PATIENT_EVENT";
    private final MrasSqlLineageAnalyzer lineageAnalyzer;

    public IndicatorDataFlowTypeResolver(MrasSqlLineageAnalyzer lineageAnalyzer) {
        this.lineageAnalyzer = lineageAnalyzer;
    }

    public FlowType resolve(EntityPageData entity) {
        if (entity == null || !entity.hasOverviewSql()) return FlowType.INCOMPLETE;
        boolean hasSource = present(entity.sourceTableSql());
        boolean hasTarget = present(entity.targetTable());
        boolean usesEvent = hasSource
                && (lineageAnalyzer.analyze(entity.sourceTableSql()).tables().contains(PATIENT_EVENT)
                    || !entity.extendedEvents().isEmpty());
        if (hasTarget && hasSource && usesEvent) return FlowType.EVENT_TO_TARGET;
        if (hasTarget && hasSource) return FlowType.DIRECT_TO_TARGET;
        if (!hasTarget) return FlowType.DIRECT_REAL_QUERY;
        return FlowType.INCOMPLETE;
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    public enum FlowType {
        EVENT_TO_TARGET,
        DIRECT_TO_TARGET,
        DIRECT_REAL_QUERY,
        INCOMPLETE
    }
}
