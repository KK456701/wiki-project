# Agent Runtime 摘要与 Spring AI 说明

当前系统已经完成 Java 17、Spring Boot 3.5、Spring AI 和 Vue 3 单运行时迁移。完整权威说明见 [agent-runtime-current.md](agent-runtime-current.md)。

Spring AI 在本项目中用于统一 Ollama、DeepSeek 和阿里云百炼等模型调用，不接管业务编排。Planner、Replanner 和 Final Answer 使用模型；CompiledPlan IR、Capability 注册表、状态控制器、ToolGateway、SQL 安全、Evidence 验证和差异诊断 Workflow 均由 Java 确定性代码控制。

项目不使用 LangChain/LangGraph，也不采用自由 ReAct。原因是医疗指标口径、医院隔离、SQL 执行和患者明细访问必须可审计、可复现，并在服务端存在不可绕过的权限与契约门禁。
