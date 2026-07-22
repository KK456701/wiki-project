/**
 * 单指标与复合指标 Agent 的运行状态、执行循环和 Trace 事件。
 *
 * <p>每个子任务拥有独立状态、Evidence 命名空间和 Trace 泳道；复合任务合并时保持用户输入顺序，不共享可变执行游标。</p>
 */
package com.hospital.wikiagent.agent.runtime;
