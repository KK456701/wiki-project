/**
 * 按医院、用户和会话隔离的多轮对话记忆。
 *
 * <p>会话只保留受控轮数和结构化指标状态，跨医院内容、令牌、密码及患者明细不得写入模型上下文。</p>
 */
package com.hospital.wikiagent.agent.memory;
