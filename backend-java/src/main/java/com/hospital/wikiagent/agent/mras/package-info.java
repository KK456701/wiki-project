/**
 * 知识库（knowledge-index-mras）只读接入层。
 *
 * <p>职责边界：解析实体页 Markdown、渲染 #ETC/#EQUALS 模板 SQL、映射 Agent 参数；
 * 不修改知识库原文件，不直连数据库，SQL 执行仍走 DBHub MCP。</p>
 */
package com.hospital.wikiagent.agent.mras;
