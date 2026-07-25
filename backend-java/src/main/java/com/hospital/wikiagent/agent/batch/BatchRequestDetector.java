package com.hospital.wikiagent.agent.batch;

import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * 用确定性正则识别“计算全部指标结果”这类批量请求。
 *
 * <p>批量场景的计划完全确定（意图固定为试运行、指标可枚举、时间来自父请求），因此识别
 * 不依赖小模型判断，避免 7B 模型在批量意图识别上不稳定。只有同时命中“全部范围词”“结果词”
 * 且不含“定义/口径”等排除词时才判定为批量试运行；否则交回原有单指标/复合路径。</p>
 */
@Component
public class BatchRequestDetector {

    /** 表示“全部指标”的范围词，要求与“指标”紧邻（可插入核心/重点）。 */
    private static final Pattern SCOPE_ALL = Pattern.compile(
            "(所有|全部|全院)(核心|重点)?指标"
                    + "|全指标|每一项指标|每个指标|各项指标"
                    + "|逐一(计算|算)|逐个(计算|算)");

    /** 表示用户想要“计算结果”而非口径解释的动词/名词。 */
    private static final Pattern WANTS_RESULT = Pattern.compile(
            "计算|结果|数值|试运行|得分|监测|达标|算一遍|算一下|都算");

    /** 命中即视为口径/定义类问题，不进入批量试运行。 */
    private static final Pattern WANTS_DEFINITION = Pattern.compile(
            "定义|口径|公式|是什么|什么意思|怎么算|如何计算|解释|含义");

    /**
     * 判断一条用户问题是否应进入批量指标计算路径。
     */
    public BatchRequestSpec detect(String query) {
        if (query == null || query.isBlank()) {
            return BatchRequestSpec.notBatch();
        }
        String normalized = normalize(query);
        if (!normalized.contains("指标")) {
            return BatchRequestSpec.notBatch();
        }
        if (WANTS_DEFINITION.matcher(normalized).find()) {
            return BatchRequestSpec.notBatch();
        }
        boolean scopeAll = SCOPE_ALL.matcher(normalized).find();
        boolean wantsResult = WANTS_RESULT.matcher(normalized).find();
        if (scopeAll && wantsResult) {
            return BatchRequestSpec.allActive(query);
        }
        return BatchRequestSpec.notBatch();
    }

    private static String normalize(String value) {
        return value.replaceAll("\\s+", "");
    }
}
