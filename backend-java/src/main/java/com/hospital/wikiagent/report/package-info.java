/**
 * 批次调研报告的不可变快照、版本管理和多格式导出。
 *
 * <p>本包只消费已持久化且完成权限隔离的批次事实，不负责指标计算、数据抽取或口径选择；
 * Word、PDF、Excel 必须由同一快照生成，并对每次下载保留审计记录。</p>
 */
package com.hospital.wikiagent.report;
