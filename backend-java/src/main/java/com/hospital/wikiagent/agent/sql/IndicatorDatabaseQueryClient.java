package com.hospital.wikiagent.agent.sql;

import java.util.List;
import java.util.Map;

/**
 * 按固定角色访问业务库和真实库的只读查询边界。
 *
 * <p>实现必须把角色映射到唯一 DBHub 工具，不得在 Java 中保存数据库凭据或建立
 * JDBC 写入旁路。调用方提供的 SQL 已由 Wiki 契约、参数绑定和只读校验保护；
 * 返回行只在受控 Workflow 内比较，患者级内容不得进入通用 Trace。</p>
 */
public interface IndicatorDatabaseQueryClient {
    List<Map<String, Object>> execute(DatabaseRole role, String sql);

    String sourceId(DatabaseRole role);
}
