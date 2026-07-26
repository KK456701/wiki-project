/**
 * 定义医院源数据抽取的强类型边界、幂等请求、安全回执和进程内并发控制。
 *
 * <p>本包不实现医院写库协议，也不允许 Agent 或 DBHub 直接写真实库。具体 HTTP
 * 适配器必须实现 {@code SourceExtractionGateway}，并继续遵守 Wiki SQL 来源、
 * 单轮一次调用、医院隔离和失败即停止的约束。</p>
 */
package com.hospital.wikiagent.agent.extraction;
