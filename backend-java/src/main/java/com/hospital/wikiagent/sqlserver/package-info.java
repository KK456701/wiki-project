/**
 * 提供试运行真实库的最小权限 JDBC 配置、对象白名单和连接身份验证。
 *
 * <p>本包不承载指标 SQL 或业务查询，只允许抽取网关在全局快照锁保护下替换
 * {@code winex_aima.dbo} 的固定表集合；任何其他数据库写入均不属于本包职责。</p>
 */
package com.hospital.wikiagent.sqlserver;
