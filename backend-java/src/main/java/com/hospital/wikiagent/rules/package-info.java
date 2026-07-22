/**
 * 直接读取 Wiki 规则、医院覆盖口径、字段映射和 SQL 规格。
 *
 * <p>规则正文、本院覆盖和 SQL 规格直接来自版本化 Wiki；生产查询不依赖 MySQL 知识库，也不读取患者业务数据。</p>
 */
package com.hospital.wikiagent.rules;
