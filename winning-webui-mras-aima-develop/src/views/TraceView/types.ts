/** 链路追踪阶段 ID */
export type FlowStage =
  'context' | 'planning' | 'compilation' | 'execution' | 'verification' | 'answer';

/** 节点类型 */
export type TraceNodeType = 'llm' | 'tool' | 'database' | 'code' | 'storage';

/** 节点执行状态 */
export type TraceNodeStatus = 'success' | 'failed' | 'error';

/** 边类型 */
export type TraceEdgeType = 'parent' | 'sequence' | 'replan' | 'failure';

/** Trace 最终状态 */
export type TraceFinalStatus = 'running' | 'success' | 'failed' | 'incomplete';

/** 耗时汇总 */
export interface TimingSummary {
  llmMs: number;
  toolMs: number;
  codeMs: number;
  storageMs: number;
}

/** 能力就绪状态 */
export interface CapabilityReadiness {
  知识治理状态: string;
  SQL展示能力: boolean;
  双库概览试算能力: boolean;
  科室明细诊断能力: boolean;
  患者明细诊断能力: boolean;
}

/** API 响应中的完整 Trace 节点 */
export interface TraceNodeFull {
  id: number;
  traceId: string;
  nodeId: string;
  nodeName: string;
  nodeType: TraceNodeType;
  status: TraceNodeStatus;
  inputSummary: string | null;
  outputSummary: string | null;
  errorCode: string | null;
  errorMessage: string | null;
  toolName: string | null;
  dbSource: string | null;
  sqlId: string | null;
  runId: string | null;
  ruleId: string | null;
  llmModel: string | null;
  modelId: string | null;
  startedAt: string;
  endedAt: string | null;
  durationMs: number;
  parentNodeId: string | null;
  subtaskId: string;
  sequence: number;
  startedOffsetMs: number;
  exclusiveDurationMs: number;
  capability: string | null;
  failureClass: string | null;
  inputTokens: number | null;
  outputTokens: number | null;
  cacheReused: number;
  retryCount: number;
  createdAt: string;
  nodeTitle: string;
  processingSummary: string;
  flowStage: FlowStage;
  flowStageTitle: string;
  flowStageOrder: number;
  inputData: Record<string, unknown> | null;
  outputData: Record<string, unknown> | null;
  capabilityReadiness: CapabilityReadiness | null;
}

/** API 响应中的 Trace 边 */
export interface TraceEdgeFull {
  fromNodeId: string;
  toNodeId: string;
  edgeType: TraceEdgeType;
  label: string;
}

/** API 响应中的 Evidence */
export interface TraceEvidenceFull {
  evidenceId: string;
  factType: string;
  ruleId: string;
  ruleVersion: string;
  statStart: string;
  statEnd: string;
  sourceTool: string;
  sourceObjectId: string;
  createdAt: string;
  expiresAt: string;
}

/** API 完整响应 */
export interface TraceDetailFull {
  id: number;
  traceId: string;
  sessionId: string | null;
  hospitalId: string;
  userId: string;
  userQuery: string;
  intent: string;
  finalStatus: TraceFinalStatus;
  finalAnswerSummary: string;
  errorCount: number;
  fallbackCount: number;
  startedAt: string;
  endedAt: string | null;
  durationMs: number;
  createdAt: string;
  nodes: TraceNodeFull[];
  flowEdges: TraceEdgeFull[];
  evidence: TraceEvidenceFull[];
  traceVersion: string;
  timingSummary: TimingSummary;
}

/** 错误类型常量 */
export const TRACE_ERROR_CODE = {
  NOT_FOUND: 'TRACE_NOT_FOUND',
} as const;
