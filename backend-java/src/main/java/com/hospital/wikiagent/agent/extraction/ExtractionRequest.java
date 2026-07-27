package com.hospital.wikiagent.agent.extraction;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 传给医院源数据抽取适配器的不可变请求。
 *
 * <p>{@code sourceSql} 只能来自当前不可变知识发布包中的 Profile ETL 引用，并携带
 * 摘要供网关复核；浏览器、模型和公开 API 不能提交或覆盖该 SQL。</p>
 */
public record ExtractionRequest(
        String traceId,
        String subtaskId,
        String hospitalId,
        String userId,
        String releaseId,
        String ruleId,
        String profileId,
        LocalDateTime statStart,
        LocalDateTime statEnd,
        String sourceSql,
        String sourceSqlSha256,
        Map<String, Object> parameters,
        String businessSourceId,
        String realSourceId,
        String idempotencyKey,
        Map<String, Object> extractionContract,
        Long hospitalSoid) {

    public ExtractionRequest {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        extractionContract = extractionContract == null ? Map.of() : Map.copyOf(extractionContract);
    }
}
