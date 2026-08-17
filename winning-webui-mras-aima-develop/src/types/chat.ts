/** SSE 事件类型枚举 */
export const SSE_EVENT = {
  AGENT_START: 'agent_start',
  TRACE_NODE: 'trace_node',
  MODEL_START: 'model_start',
  TOOL_CALL: 'tool_call',
  TOOL_RESULT: 'tool_result',
  STAGE_UPDATE: 'stage_update',
  CLARIFICATION_REQUIRED: 'clarification_required',
  ASSISTANT_MESSAGE: 'assistant_message',
  WORKSPACE_REDIRECT: 'workspace_redirect',
  BATCH_INDICATOR_RESULT: 'batch_indicator_result',
  AGENT_DONE: 'agent_done',
  AGENT_ERROR: 'agent_error',
} as const;

export type SseEventType = (typeof SSE_EVENT)[keyof typeof SSE_EVENT];

/** Agent 运行状态 */
export const AGENT_STATUS = {
  RUNNING: 'running',
  COMPLETED: 'completed',
  INCOMPLETE: 'incomplete',
  FAILED: 'failed',
} as const;

/** 停止原因 */
export const STOP_REASON = {
  FINAL_ANSWER: 'final_answer',
  CLARIFICATION: 'clarification',
  TOOL_ERROR: 'tool_error',
} as const;

/** 澄清选项 */
export interface ClarificationOption {
  id: string;
  label: string;
  value: string;
  description: string;
  group: string;
}

/** 澄清对象 */
export interface Clarification {
  code: string;
  kind: string;
  title: string;
  question: string;
  helpText: string;
  selectionMode: 'single' | 'multiple';
  options: ClarificationOption[];
  allowFreeText: boolean;
  freeTextPlaceholder: string;
  resumePrefix: string;
  clarificationId: string;
  field: 'indicator' | 'intent' | 'caliber' | 'time' | 'free_text';
  resumeToken: string;
}

/** SSE 事件基础结构 */
export interface SseEventBase {
  event: SseEventType;
  traceId: string;
  /** 后端返回的对话 ID，每个事件类型都会携带；首条消息时用于替换前端占位 ID */
  sessionId?: string;
}

/** Trace 链路节点（存储用） */
export interface TraceNode {
  nodeId: string;
  nodeName: string;
  nodeTitle?: string;
  nodeType: string;
  status: string;
  startedAt: string;
  endedAt?: string;
  durationMs: number;
  subtaskId: string;
  inputData: Record<string, unknown> | string;
  outputData: Record<string, unknown> | string;
  processingSummary?: string;
  errorCode?: string;
  errorMessage?: string;
  modelId?: string;
  toolName?: string;
}

/** 链路详情响应 */
export interface TraceDetailResponse {
  traceId: string;
  sessionId?: string;
  hospitalId: string;
  userId?: string;
  userQuery?: string;
  intent?: string;
  finalStatus: string;
  finalAnswerSummary?: string;
  errorCount: number;
  fallbackCount: number;
  startedAt: string;
  endedAt?: string;
  durationMs?: number;
  createdAt: string;
  traceVersion: string;
  timingSummary: {
    llmMs: number;
    toolMs: number;
    codeMs: number;
    storageMs: number;
  };
  nodes: TraceNode[];
  evidence: unknown[];
}

/** trace_node 事件 */
export interface TraceNodeEvent extends SseEventBase {
  event: typeof SSE_EVENT.TRACE_NODE;
  nodeId: string;
  nodeName: string;
  nodeTitle?: string;
  nodeType: 'llm' | 'code' | 'tool' | 'storage';
  status: 'success' | 'failed' | 'warning';
  startedAtEpochMs: number;
  endedAtEpochMs: number;
  durationMs: number;
  subtaskId: string;
  input: Record<string, unknown>;
  output: Record<string, unknown>;
  processingSummary?: string;
  errorCode?: string;
  errorMessage?: string;
}

/** agent_start 事件 */
export interface AgentStartEvent extends SseEventBase {
  event: typeof SSE_EVENT.AGENT_START;
  status: string;
}

/** model_start 事件 */
export interface ModelStartEvent extends SseEventBase {
  event: typeof SSE_EVENT.MODEL_START;
  message: string;
  step?: number;
}

/** tool_call 事件 */
export interface ToolCallEvent extends SseEventBase {
  event: typeof SSE_EVENT.TOOL_CALL;
  step: number;
  toolName: string;
  status: string;
}

/** tool_result 事件 */
export interface ToolResultEvent extends SseEventBase {
  event: typeof SSE_EVENT.TOOL_RESULT;
  step: number;
  toolName: string;
  status: string;
  code: string;
  message: string;
  retryable: boolean;
  reused: boolean;
  durationMs: number;
}

/** stage_update 事件 */
export interface StageUpdateEvent extends SseEventBase {
  event: typeof SSE_EVENT.STAGE_UPDATE;
  nodeName: string;
  nodeType: string;
  status: string;
  durationMs?: number;
  message?: string;
  subtaskId?: string;
}

/** clarification_required 事件 */
export interface ClarificationRequiredEvent extends SseEventBase {
  event: typeof SSE_EVENT.CLARIFICATION_REQUIRED;
  step: number;
  message: string;
  code: string;
  stopReason: string;
  clarification: Clarification;
}

/** assistant_message 事件 */
export interface AssistantMessageEvent extends SseEventBase {
  event: typeof SSE_EVENT.ASSISTANT_MESSAGE;
  step: number;
  message: string;
  status: string;
}

export const WORKSPACE = {
  INDICATOR_DIAGNOSIS: 'indicator_diagnosis',
} as const;

export interface WorkspaceRedirect {
  workspace: (typeof WORKSPACE)[keyof typeof WORKSPACE];
  step: 'selection';
  mode: 'blank' | 'prefill' | 'candidate_selection';
  ruleId?: string;
  ruleName?: string;
  profileId?: string;
  statStart?: string;
  statEnd?: string;
  candidateIndicators?: Array<{ ruleId: string; ruleName: string }>;
}

export interface WorkspaceRedirectEvent extends SseEventBase, WorkspaceRedirect {
  event: typeof SSE_EVENT.WORKSPACE_REDIRECT;
  step: 'selection';
}

/** agent_done 事件 */
export interface AgentDoneEvent extends SseEventBase {
  event: typeof SSE_EVENT.AGENT_DONE;
  step: number;
  stopReason: string;
  status: string;
  stepCount: number;
  message?: string;
}

/** agent_error 事件 */
export interface AgentErrorEvent extends SseEventBase {
  event: typeof SSE_EVENT.AGENT_ERROR;
  step: number;
  message: string;
  failureCode: string;
  stopReason: string;
  status: string;
}

/** batch_indicator_result 事件 */
export interface BatchIndicatorResultEvent extends SseEventBase {
  event: typeof SSE_EVENT.BATCH_INDICATOR_RESULT;
  step: number;
  batchRunId: string;
  ruleId: string;
  ruleName: string;
  profileId?: string;
  profileLabel?: string;
  status: 'SUCCESS' | 'NO_SAMPLE' | 'FAILED';
  done: number;
  total: number;
  resultValue?: number;
  numeratorCount?: number;
  denominatorCount?: number;
  sampleCount?: number;
  targetValue?: number | string;
  targetDirection?: string;
  unit?: string;
  calculationDisplay?: string;
  statStart?: string;
  statEnd?: string;
  runId?: string;
  dataFreshness?: string;
  qualityStatus?: 'NORMAL' | 'ABNORMAL';
  errorCode?: string;
  errorMessage?: string;
  overviewSqlHash?: string;
  detailKind?: string;
  detailContractVersion?: string;
}

/** SSE 事件联合类型 */
export type SseEvent =
  | AgentStartEvent
  | ModelStartEvent
  | ToolCallEvent
  | ToolResultEvent
  | StageUpdateEvent
  | ClarificationRequiredEvent
  | AssistantMessageEvent
  | WorkspaceRedirectEvent
  | BatchIndicatorResultEvent
  | AgentDoneEvent
  | AgentErrorEvent
  | TraceNodeEvent;

/** 聊天消息角色 */
export const MESSAGE_ROLE = {
  USER: 'user',
  ASSISTANT: 'assistant',
  SYSTEM: 'system',
} as const;

/** 消息状态 */
export const MESSAGE_STATUS = {
  PENDING: 'pending',
  STREAMING: 'streaming',
  COMPLETED: 'completed',
  ERROR: 'error',
  STOPPED: 'stopped',
} as const;

/** 澄清处理状态 */
export const CLARIFICATION_STATUS = {
  /** 待处理（弹窗待弹出） */
  PENDING: 'pending',
  /** 已确认（用户已选择并确认） */
  CONFIRMED: 'confirmed',
  /** 已忽略（用户看到澄清弹窗但未做选择就关闭了） */
  DISMISSED: 'dismissed',
} as const;

export type ClarificationStatus = (typeof CLARIFICATION_STATUS)[keyof typeof CLARIFICATION_STATUS];

/** 批量作业快照 */
export interface BatchJobSnapshot {
  batchRunId: string;
  hospitalId: string;
  userId: string;
  status: 'RUNNING' | 'FINISHED';
  total: number;
  succeeded: number;
  noSample: number;
  failed: number;
  statStart: string;
  statEnd: string;
  traceId: string;
  createdAt: string;
  finishedAt: string | null;
}

/** 批量任务快照 */
export interface BatchTaskSnapshot {
  batchRunId: string;
  position: number;
  ruleId: string;
  ruleName: string;
  profileId: string | null;
  profileName: string | null;
  status: 'SUCCESS' | 'NO_SAMPLE' | 'FAILED';
  resultValue: number | null;
  numeratorCount: number | null;
  denominatorCount: number | null;
  sampleCount: number | null;
  unit: string;
  targetValue: string;
  targetDirection: 'up' | 'down';
  qualityStatus: string;
  dataFreshness: string | null;
  statStart: string;
  statEnd: string;
  overviewSqlHash: string | null;
  detailKind: string | null;
  detailContractVersion: string | null;
  detailSnapshotId: string | null;
  calculationDisplay: string | null;
  errorCode: string | null;
  errorMessage: string | null;
}

/** 批量运行响应 */
export interface BatchRunResponse {
  job: BatchJobSnapshot;
  tasks: BatchTaskSnapshot[];
}

/** 报告计数汇总 */
export interface BatchReportCounts {
  success: number;
  noSample: number;
  failed: number;
}

/** 批次报告快照（对应 POST /api/batch-runs/{batchRunId}/reports 与 GET /api/batch-reports/{reportId}） */
export interface BatchReportSnapshot {
  reportId: string;
  version: number;
  reportStatus: 'FORMAL' | 'DRAFT';
  batchRunId: string;
  hospitalId: string;
  statStart: string;
  statEnd: string;
  generatedAt: string;
  total: number;
  counts: BatchReportCounts;
  tasks: BatchTaskSnapshot[];
  statement: string;
}

/** 报告下载格式 */
export type ReportDownloadFormat = 'docx' | 'pdf' | 'xlsx';

// ============ dataFlow 数据链路类型 ============

/** 数据链路节点类型枚举 */
export const DATA_FLOW_NODE_TYPE = {
  TABLE: 'TABLE',
  SOURCE_EXTRACT_SQL: 'SOURCE_EXTRACT_SQL',
  EXTENDED_EVENT_SQL: 'EXTENDED_EVENT_SQL',
  OVERVIEW_SQL: 'OVERVIEW_SQL',
  DEPARTMENT_SQL: 'DEPARTMENT_SQL',
  PATIENT_SQL: 'PATIENT_SQL',
  RESULT: 'RESULT',
  CONFIGURATION: 'CONFIGURATION',
} as const;

export type DataFlowNodeType = (typeof DATA_FLOW_NODE_TYPE)[keyof typeof DATA_FLOW_NODE_TYPE];

/** 数据库角色枚举 */
export const DATA_FLOW_DB_ROLE = {
  BUSINESS: 'BUSINESS',
  SYNC: 'SYNC',
  REAL: 'REAL',
  KNOWLEDGE: 'KNOWLEDGE',
} as const;

export type DataFlowDatabaseRole = (typeof DATA_FLOW_DB_ROLE)[keyof typeof DATA_FLOW_DB_ROLE];

/** 链路模板类型枚举 */
export const DATA_FLOW_TEMPLATE = {
  EVENT_TO_TARGET: 'EVENT_TO_TARGET',
  DIRECT_TO_TARGET: 'DIRECT_TO_TARGET',
  DIRECT_REAL_QUERY: 'DIRECT_REAL_QUERY',
  INCOMPLETE: 'INCOMPLETE',
} as const;

export type DataFlowTemplateType = (typeof DATA_FLOW_TEMPLATE)[keyof typeof DATA_FLOW_TEMPLATE];

/** 数据链路节点对象 */
export interface DataFlowNode {
  id: string;
  sequence: number;
  title: string;
  nodeType: DataFlowNodeType;
  databaseRole: DataFlowDatabaseRole;
  tableNames: string[];
  sqlKind: string;
  /** 知识库原始模板 SQL；编辑和保存时使用，避免提交已绑定统计周期的展示脚本 */
  templateSql?: string;
  sql: string;
  parameters: string[];
  description: string;
  tableDescriptions?: Record<string, string>;
  primaryTables?: string[];
  parameterTables?: string[];
}

/** 数据链路边对象 */
export interface DataFlowEdge {
  from: string;
  to: string;
  label: string;
}

/** dataFlow 顶层结构 */
export interface DataFlow {
  templateType: DataFlowTemplateType;
  templateLabel: string;
  status: 'complete' | 'incomplete';
  warnings: string[];
  primaryTables: string[];
  parameterTables: string[];
  nodes: DataFlowNode[];
  edges: DataFlowEdge[];
}

// ============ 指标规则 API 类型 ============

/** 生效规则快照（GET /api/kb/rules/{ruleId}/effective） */
export interface EffectiveRule {
  ruleId: string;
  indexCode: string;
  ruleName: string;
  category: string;
  hospitalId: string;
  effectiveLevel: string;
  profileId: string;
  profileName: string;
  executionStatus: 'executable' | 'documentation_only';
  executionBlockers: string[];
  definition: string;
  formula: string;
  numeratorRule: string;
  denominatorRule: string;
  filterRule: string;
  excludeRule: string;
  implementationStatus: string;
  standardSql: string;
  sourceExtractSql: string;
  departmentDetailSql: string;
  patientDetailSql: string;
  sqlStatus: 'available' | 'overview_static_validated' | 'unavailable';
  overviewRuntimeEligible: boolean;
  resultUnit: string;
  ruleSource: 'wiki' | 'mras';
  significance?: string;
  dataFlow?: DataFlow;
  system?: string;
  caliber?: string;
  dataSource?: string;
  warnings: string[];
  relations: Record<string, string[]>;
  fallbackChain: string[];
  calculationDefinition?: Record<string, unknown>;
  resultContract?: Record<string, unknown>;
  resultMapping?: Record<string, unknown>;
}

/** 指标明细分页结果（GET /api/kb/rules/{ruleId}/details） */
export interface RuleDetailPage {
  ruleId: string;
  ruleName: string;
  batchRunId: string;
  group: DetailGroup;
  statStart: string;
  statEnd: string;
  page: number;
  pageSize: number;
  rowCount: number;
  rows: Record<string, unknown>[];
  truncated: boolean;
  snapshotId: string;
  snapshotReused: boolean;
  durationMs: number;
  sqlSource: string;
  detailKind: string;
  detailContractVersion: string;
  cardNumerator: number;
  cardDenominator: number;
  detailNumerator: number;
  detailDenominator: number;
  overviewSqlHash: string;
  groups: DetailGroupDescriptor[];
  summary: Record<string, unknown>;
}

/** 明细分组类型 */
export const DETAIL_GROUP = {
  NUMERATOR: 'numerator',
  DENOMINATOR: 'denominator',
  DIFFERENCE: 'difference',
  CONTRIBUTIONS: 'contributions',
  SAMPLES: 'samples',
  ACTUAL: 'actual',
  REGISTERED: 'registered',
  LEVEL4_HIT: 'level4Hit',
  LEVEL4_TOTAL: 'level4Total',
  LEVEL4_MISS: 'level4Miss',
  LEVEL3_HIT: 'level3Hit',
  LEVEL3_TOTAL: 'level3Total',
  LEVEL3_MISS: 'level3Miss',
} as const;

export type DetailGroup = (typeof DETAIL_GROUP)[keyof typeof DETAIL_GROUP];

export interface DetailGroupDescriptor {
  key: DetailGroup;
  label: string;
  semantic: string;
  rowCount: number;
}

/** 明细查询参数 */
export interface RuleDetailQuery {
  ruleId: string;
  group?: DetailGroup;
  batchRunId: string;
  start?: string;
  end?: string;
  profileId?: string | null;
  page?: number;
  pageSize?: number;
}

/** 口径/链路查询参数 */
export interface RuleEffectiveQuery {
  ruleId: string;
  profileId?: string | null;
  statStart: string;
  statEnd: string;
}

/** 指标口径（GET /api/kb/rules/{ruleId}/profiles） */
export interface RuleProfile {
  profileId: string;
  profileName: string;
  label: string;
  status: string;
  governanceStatus: string;
  executionStatus: string;
  overviewRuntimeEligible: boolean;
  parameterOverrides: Record<string, unknown>;
  fieldRoleOverrides: Record<string, unknown>;
  numeratorRule: string;
  denominatorRule: string;
  timeDimension: string;
}

/** 批量指标结果项（batchResults 数组元素，与 SSE batch_indicator_result 字段一致） */
export interface BatchResultItem {
  ruleId: string;
  ruleName: string;
  status: 'SUCCESS' | 'NO_SAMPLE' | 'FAILED';
  done: number;
  total: number;
  qualityStatus: 'NORMAL' | 'ABNORMAL';
  batchRunId?: string;
  profileId?: string;
  profileLabel?: string;
  resultValue?: number;
  numeratorCount?: number;
  denominatorCount?: number;
  sampleCount?: number;
  unit?: string;
  targetValue?: string | number;
  targetDirection?: 'up' | 'down';
  calculationDisplay?: string;
  statStart?: string;
  statEnd?: string;
  runId?: string;
  dataFreshness?: string;
  detailKind?: string;
  detailContractVersion?: string;
  overviewSqlHash?: string;
  errorCode?: string;
  errorMessage?: string;
}

/** 消息列表 API 响应 */
export interface MessageResponse {
  role: 'user' | 'assistant';
  content: string;
  ruleId: string | null;
  ruleName: string | null;
  statStart: string | null;
  statEnd: string | null;
  runId: string | null;
  createdAt: string;
  batchResults: BatchResultItem[] | null;
}

/** 聊天消息 */
export interface ChatMessage {
  id: string;
  role: (typeof MESSAGE_ROLE)[keyof typeof MESSAGE_ROLE];
  content: string;
  status: (typeof MESSAGE_STATUS)[keyof typeof MESSAGE_STATUS];
  traceId?: string;
  stepCount?: number;
  /** 规则 ID（历史消息加载时填充） */
  ruleId?: string;
  /** 规则名称（历史消息加载时填充） */
  ruleName?: string;
  /** 统计开始时间（历史消息加载时填充） */
  statStart?: string;
  /** 统计结束时间（历史消息加载时填充） */
  statEnd?: string;
  /** 试运行 ID（历史消息加载时填充） */
  runId?: string;
  /** 工具调用阶段信息 */
  stages: StageInfo[];
  /** 当前阶段描述 */
  currentStage?: string;
  /** 当前阶段耗时（毫秒），来自 stage_update SSE 事件 */
  currentStageDurationMs?: number;
  /** 当前阶段子任务分组 ID，来自 stage_update SSE 事件 */
  currentSubtaskId?: string;
  /** 澄清数据 */
  clarification?: Clarification;
  /** 澄清处理状态 */
  clarificationStatus?: ClarificationStatus;
  /** 用户选择的澄清值（确认后记录） */
  clarificationAnswer?: string[];
  /** 错误信息 */
  errorMessage?: string;
  /** 完整链路节点 */
  traceNodes: TraceNode[];
  /** 批量指标卡片载荷（历史消息加载时填充，仅助手消息中存在） */
  batchResults?: BatchResultItem[];
  createdAt: number;
}

/** 阶段信息 */
export interface StageInfo {
  step: number;
  toolName?: string;
  message?: string;
  status: string;
  durationMs?: number;
}

/** 对话列表 API 响应 */
export interface SessionResponse {
  sessionId: string;
  title: string;
  lastMessageAt: string;
  messageCount: number;
}

/** 对话 */
export interface ChatSession {
  id: string;
  title: string;
  messages: ChatMessage[];
  modelId?: string;
  messageCount?: number;
  createdAt: number;
  updatedAt: number;
}

/** 聊天请求参数 */
export interface ChatRequest {
  query: string;
  sessionId?: string;
  modelId?: string;
  fileKey?: string;
  clarificationResponse?: {
    clarificationId: string;
    selectedOptionIds: string[];
    resumeToken: string;
  };
}

/** 文件上传响应 */
export interface UploadResponse {
  fileKey: string;
  fileName: string;
  sizeBytes: number;
}

/** Agent 能力 */
export interface AgentCapabilities {
  enabled: boolean;
  defaultModel: string;
  models: ModelInfo[];
  streaming: boolean;
  maxSteps: number;
  orchestration: string;
}

/** 模型信息 */
export interface ModelInfo {
  id: string;
  name: string;
  provider: string;
  available: boolean;
}

// ============ 初始化校验相关类型 ============

/** 校验影响等级 */
export type ImpactLevel = 'CONFIRMED' | 'POSSIBLE' | 'DISPLAY_ONLY' | 'UNKNOWN' | 'NO_IMPACT';

/** 校验严重程度 */
export type ValidationSeverity = 'BLOCKED' | 'NO_SAMPLE' | 'WARNING' | 'NORMAL';

/** 校验问题类型 */
export type ValidationCategory =
  | 'MISSING_TABLE'
  | 'MISSING_COLUMN'
  | 'NO_DATA'
  | 'NULL_RATE'
  | 'JOIN_COVERAGE'
  | 'UNSUPPORTED'
  | 'NOT_IMPLEMENTED'
  | 'UPSTREAM_NOT_REGISTERED'
  | 'DATABASE_CONNECTION';

/** 字段角色 */
export type FieldRole =
  | 'TIME_FILTER'
  | 'NUMERATOR_CONDITION'
  | 'DENOMINATOR_SCOPE'
  | 'JOIN_KEY'
  | 'GROUP_KEY'
  | 'DISTINCT_KEY'
  | 'SELECT_ONLY';

/** 校验明细条目 */
export interface ValidationItem {
  ruleId: string;
  ruleName: string;
  profileId: string;
  profileLabel: string;
  impactLevel: ImpactLevel;
  severity: ValidationSeverity;
  category: ValidationCategory;
  databaseRole: string;
  tableName: string;
  fieldName: string;
  fieldLabel: string;
  fieldRoles: FieldRole[];
  sourceSystem?: string;
  queryScope?: string;
  unresolvedSymbols?: string[];
  queryBlockPaths?: string[];
  actualCount?: number;
  nullCount?: number;
  totalCount?: number;
  rate?: number;
  matchedCount?: number;
  unmatchedCount?: number;
  affectsCalculation?: boolean;
  issueSummary: string;
  action: string;
  message: string;
  errorCode?: string;
  repairSuggestion?: string;
  repairOwner?: string;
  knowledgePatchTemplate?: string;
  sql?: string;
  parameters?: Record<string, unknown>;
  durationMs?: number;
  returnedRows?: number;
  databaseError?: string;
  evidenceCount: number;
}

/** 口径级窗口数据 */
export interface ProfileWindowInfo {
  profileId: string;
  businessSourceCount?: number;
}

/** 初始化校验输出（traceNode.outputData 解析后的结构） */
export interface InitializationOutput {
  batchRunId: string;
  qualityStatus: string;
  indicatorCount: number;
  profileCount: number;
  runnableCount: number;
  noSampleCount: number;
  blockedCount: number;
  skippedCount: number;
  missingTableCount: number;
  missingColumnCount: number;
  emptySourceCount: number;
  nullFieldCount: number;
  joinGapCount: number;
  unsupportedCount: number;
  distinctNullFieldCount: number;
  distinctJoinGapCount: number;
  distinctUnsupportedCount: number;
  businessConnected: boolean;
  realConnected: boolean;
  durationMs: number;
  reused: boolean;
  statStart: string;
  statEnd: string;
  realDataStatus?: string;
  profiles: ProfileWindowInfo[];
  items: ValidationItem[];
}

// ============ 运行时设置（SettingsDrawer） ============

/** 数据库连接设置（GET /api/settings/runtime 返回的 database 项） */
export interface RuntimeDatabaseSetting {
  id: 'business' | 'real' | 'oracle';
  name: string;
  purpose: string;
  enabled: boolean;
  configured: boolean;
  engine: string;
  endpoint: string;
  username: string;
  schema: string;
  credentialConfigured: boolean;
  pool: Record<string, number>;
  formalChain: boolean;
}

/** 运行时模型设置（GET /api/settings/runtime 返回的 model 项） */
export interface RuntimeModelSetting {
  id: string;
  name: string;
  provider: string;
  baseUrl: string;
  completionsPath: string;
  enableThinking: boolean | null;
  apiKeyConfigured: boolean;
  model?: string;
  thinking?: boolean;
  available?: boolean;
}

/** 运行时设置顶层结构 */
export interface RuntimeSettings {
  securityNotice: string;
  defaultModel: string;
  models: RuntimeModelSetting[];
  databases: RuntimeDatabaseSetting[];
}

/** 连接测试结果 */
export interface ConnectionTestResult {
  connectionId: string;
  status: 'CONNECTED' | 'FAILED' | 'DISABLED';
  message: string;
  durationMs: number;
}

/** 连接测试入参 */
export interface RuntimeConnectionTestInput {
  driverClassName: string;
  url: string;
  username: string;
  password: string;
  schema: string;
}

/** 连接保存入参 */
export interface RuntimeConnectionSaveInput extends RuntimeConnectionTestInput {
  enabled: boolean;
  maximumPoolSize?: number;
  minimumIdle?: number;
  connectionTimeoutMs?: number;
  validationQuery?: string;
}

/** 模型配置保存入参 */
export interface RuntimeModelConfigInput {
  id: string;
  name: string;
  provider: 'ollama' | 'openai-compatible';
  model: string;
  baseUrl: string;
  completionsPath: string;
  apiKey: string;
  thinking: boolean;
  enableThinking: boolean | null;
}
