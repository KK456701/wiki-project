package com.hospital.wikiagent.agent.mras;

import java.util.Set;

/**
 * 维护核心指标与五种确定性明细契约之间的显式映射。
 *
 * <p>普通 COUNT 比例仍由确定性提取器机械验证；SUM、中位数、双数据源和两率比较
 * 只按指标与口径注册键选择契约，禁止由前端、模型或不稳定的 SQL 文本特征猜测业务类型。
 * 本类型不执行查询，也不保存运行状态。</p>
 */
public final class MrasDetailContractRegistry {
    public static final String CONTRACT_VERSION = "mras-detail-v2";

    private static final Set<String> RATE_COMPARISONS = Set.of(
            "HXZD-012-001", "HXZD-012-001_001", "HXZD-012-001_002",
            "HXZD-012-002", "HXZD-012-002_001", "HXZD-012-002_002");
    private static final Set<String> DUAL_SOURCES = Set.of(
            "HXZD-012-004", "HXZD-012-004_001", "HXZD-012-004_002");

    private MrasDetailContractRegistry() {
    }

    public static MrasDetailKind kindFor(String indicatorCode, String profileId) {
        String effective = profileId == null || profileId.isBlank() ? indicatorCode : profileId;
        if ("HXZD-007-001".equals(effective) || "HXZD-007-001".equals(indicatorCode)) {
            return MrasDetailKind.SUM_CONTRIBUTION;
        }
        if ("HXZD-014-001".equals(effective) || "HXZD-014-001".equals(indicatorCode)) {
            return MrasDetailKind.MEDIAN_SAMPLE;
        }
        if (DUAL_SOURCES.contains(effective) || DUAL_SOURCES.contains(indicatorCode)) {
            return MrasDetailKind.DUAL_SOURCE;
        }
        if (RATE_COMPARISONS.contains(effective) || RATE_COMPARISONS.contains(indicatorCode)) {
            return MrasDetailKind.RATE_COMPARISON;
        }
        return MrasDetailKind.COUNT_RATIO;
    }
}
