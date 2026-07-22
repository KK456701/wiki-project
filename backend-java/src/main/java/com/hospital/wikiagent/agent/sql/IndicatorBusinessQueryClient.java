package com.hospital.wikiagent.agent.sql;

import java.util.List;
import java.util.Map;

/**
 * 定义或实现 {@code IndicatorBusinessQueryClient} 对外部服务的受控访问边界。
 */
public interface IndicatorBusinessQueryClient {
    List<Map<String, Object>> execute(String sql);

    String sourceId();
}
