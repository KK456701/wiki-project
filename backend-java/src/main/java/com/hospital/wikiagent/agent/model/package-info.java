/**
 * Ollama、OpenAI 兼容模型适配以及 Planner 和最终回答调用。
 *
 * <p>Spring AI 仅作为 Ollama/DeepSeek 文本模型适配层，不注册自动工具循环；模型输出必须经过 JSON、业务规则或 Evidence 校验。</p>
 */
package com.hospital.wikiagent.agent.model;
