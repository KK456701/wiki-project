package com.hospital.wikiagent.agent.sql;

import java.util.List;
import java.util.Map;

/**
 * 定义或实现 {@code IndicatorBusinessQueryClient} 对外部服务的受控访问边界。
 *
 * <p>客户端统一处理连接、超时和协议错误，并向上层返回稳定领域异常。认证信息、SQL 明文和患者数据不得出现在普通日志中。</p>
 */
public interface IndicatorBusinessQueryClient {
    List<Map<String, Object>> execute(String sql);

    String sourceId();
}
