package com.hospital.wikiagent.agent.extraction;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 传给医院源数据抽取适配器的不可变请求。
 *
 * <p>{@code sourceSql} 必须来自当前发布的 Wiki，调用方不得接收模型或浏览器提交的
 * SQL。具体 HTTP 协议由后续适配器转换，本领域对象不绑定同事接口的临时字段名。</p>
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
        String idempotencyKey) {

    public ExtractionRequest {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }
}
