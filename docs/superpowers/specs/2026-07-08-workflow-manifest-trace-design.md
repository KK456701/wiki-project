# 工作流 Manifest 与 Dify-lite Trace 设计

日期：2026-07-08

## 目标

当前执行链路只展示 `intent_detect`、`rule_search`、`final_response` 这类内部节点名和摘要，定位问题时仍需要开发者回到代码里猜每个节点的职责。第一阶段要学习 Dify 工作流的节点化思路，但不做拖拽编排器，先把“设计时工作流说明”和“运行时执行记录”结合起来。

完成后，用户在前端点击“查看链路”时，应能看懂：

1. 节点中文名和节点类型。
2. 节点职责。
3. 这个节点期望的入参和出参。
4. 本次运行实际入参和实际出参。
5. 节点使用的工具、模型、数据源或配置摘要。
6. 失败时优先检查哪里。

## 核心概念

### Manifest

Manifest 是设计时的工作流说明，使用 YAML 保存，路径为：

```text
app/workflows/core_indicator_chat.yaml
```

它描述“工作流应该长什么样”，包括：

- `workflow_id`
- `name`
- `description`
- `nodes`
- `edges`

节点包含：

- `id`：稳定节点 ID，例如 `intent_detect`。
- `title`：中文显示名，例如“识别用户意图”。
- `type`：节点类型，例如 `llm_or_rule`、`kb_tool`、`agent`。
- `description`：节点职责。
- `inputs`：期望输入字段。
- `outputs`：期望输出字段。
- `config`：模型、工具、数据源、超时等配置摘要。
- `failure_hint`：失败时优先排查建议。

### Trace

Trace 是运行时的实际执行记录，由 `TraceRecorder` 产生。每次请求有一个 `trace_id`，每个节点有一条运行记录。

节点运行记录包含：

- `node_name`
- `node_type`
- `status`
- `duration_ms`
- `input_summary`
- `output_summary`
- `input_data`
- `output_data`
- `config_data`
- `error_code`
- `error_message`
- `tool_name`
- `db_source`
- `rule_id`

第一阶段为了兼容已有运行库表，不立即新增数据库列。结构化 `input_data`、`output_data`、`config_data` 写入 JSONL 兜底文件；从运行库读取 Trace 时，再用同一个 `node_id` 合并 JSONL 里的结构化数据。这样不会破坏已有表结构，后续迁移机制成熟后再把结构化 JSON 正式落库。

## 数据流

```text
app/workflows/core_indicator_chat.yaml
        |
        v
WorkflowManifestLoader
        |
        v
TraceRecorder.get_trace(trace_id)
        |
        +-- 运行库 med_agent_trace / med_agent_trace_node
        +-- JSONL runtime/trace_events.jsonl
        |
        v
合并 manifest 元数据 + 运行时节点数据
        |
        v
GET /api/traces/{trace_id}
        |
        v
前端 Trace 弹窗
```

## 第一阶段范围

第一阶段只做“可读、可定位、可扩展”的 Dify-lite Trace：

1. 新增 `app/workflows/core_indicator_chat.yaml`。
2. 新增 manifest 加载器。
3. `TraceRecorder.record_node()` 支持结构化入参、出参和配置快照。
4. `TraceRecorder.get_trace()` 给每个节点补充 manifest 元数据。
5. 聊天主链路写入更清楚的节点运行数据。
6. 前端 Trace 弹窗按节点展示：概览、实际入参、实际出参、配置、故障提示。

## 非目标

第一阶段不做：

- 不做拖拽式工作流编辑器。
- 不允许前端修改 manifest。
- 不把 manifest 当成真正执行引擎。
- 不把全部业务节点一次性补齐到完美状态。
- 不强行迁移运行库表结构。

## 前端展示

每个节点显示：

```text
识别用户意图 · success
类型：LLM/规则识别  耗时：12ms
职责：判断用户是在问指标、反馈口径、生成 SQL、诊断还是普通聊天。

本次入参
{
  "query": "急会诊及时到位率怎么算？",
  "session_memory": {}
}

本次出参
{
  "intent": "query",
  "retrieval_query": "急会诊及时到位率怎么算？"
}

期望入参：query, session_memory
期望出参：intent, retrieval_query, custom_filters
故障提示：如果这里失败，优先检查意图识别 prompt、Ollama 状态或规则兜底逻辑。
```

失败节点使用红色边框和错误信息；兜底节点使用黄色提示。

## 成功标准

1. `/api/traces/{trace_id}` 返回节点中文标题、职责、期望入参、期望出参、故障提示。
2. 新产生的 Trace 节点包含结构化 `input_data` 和 `output_data`。
3. 前端 Trace 弹窗不再只显示“输入：xxx / 输出：xxx”，而是分区展示节点详情。
4. 已有 `python -B -m unittest discover -s tests -v` 通过。
5. 老 Trace 即使没有结构化 JSON，也仍能显示原来的摘要信息。
