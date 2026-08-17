/**
 * 指标异常排查（诊断案例）相关数据模型
 *
 * 结构与后端 `DiagnosisCaseSnapshot` 对齐，参考
 * winning-winex-mras-aima/docs/api/diagnosis-cases.md 与 diagnosis-case-actions.md。
 */
import type { DetailGroup, DetailGroupDescriptor } from '@/types/chat';

/** 冻结口径快照（创建案例时从规则快照复制） */
export interface CaliberSnapshot {
  ruleId: string;
  ruleName: string;
  profileId: string;
  profileName: string;
  effectiveLevel: string;
  definition: string;
  formula: string;
  numeratorRule: string;
  denominatorRule: string;
  caliber: unknown;
  dataSource: string;
  standardSql: string;
  sourceExtractSql: string;
  dataFlow: unknown;
  knowledgeReleaseId: string;
  timeRange: { start: string; end: string };
}

/** 关卡状态（来自 gateResults.status） */
export type GateStatus = 'PASSED' | 'BLOCKED' | 'PENDING' | (string & {});

/** 三关校验结果（gateResults 数组元素） */
export interface GateResult {
  gate: number;
  name: string;
  status: GateStatus;
  errorCode?: string;
  message?: string;
  repairSuggestion?: string;
  facts?: unknown;
}

/** 证据条目（CASE_INVESTIGATION 阶段累积，字段随后端回流动态追加） */
export interface EvidenceItem {
  evidenceId: string;
  submittedAt: string;
  summary: string;
  type?: string;
  runAutomatic?: boolean;
  requestAiAnalysis?: boolean;
  suspectedLayer?: string;
  requirement?: string;
  validationSql?: string;
  candidateSql?: string;
  patchConditions?: Array<{ field: string; operator: string; value: string }>;
  requirementAnalysis?: Record<string, unknown>;
  sqlContext?: Record<string, unknown>;
  /** 自动取证回流的三阶段取证汇总（runAutomatic=true 时后端 putAll 合并进证据顶层，与 type 无关） */
  display?: {
    found?: string[];
    notFound?: string[];
    unfinished?: string[];
    conclusion?: string;
    nextAction?: string;
  };
  /** 自动取证各阶段明细（与 display 同源回流） */
  stages?: Array<{
    stage?: string;
    databaseRole?: string;
    sql?: string;
    status?: string;
    rowCount?: number;
    rows?: unknown[];
    meaning?: string;
    error?: string;
    reason?: string;
  }>;
  identifierMapping?: Record<string, unknown>;
  allStagesCompleted?: boolean;
  aiAnalysis?: string;
}

/** 诊断案例快照——前端驱动排查 UI 的唯一真相来源 */
export interface DiagnosisCaseSnapshot {
  caseId: string;
  hospitalId: string;
  userId: string;
  sessionId: string;
  status: string;
  currentStep: string;

  ruleId: string;
  profileId: string;
  knowledgeReleaseId: string;
  modelId: string;

  caseInput: Record<string, unknown>;
  caliberSnapshot: CaliberSnapshot;
  caseExpectedClassification: Record<string, unknown>;

  gateResults: GateResult[];
  evidence: EvidenceItem[];
  causeConclusion: Record<string, unknown>;
  changeProposal: Record<string, unknown>;
  candidateSql: Record<string, unknown>;
  shadowTrial: Record<string, unknown>;
  draftResult: Record<string, unknown>;
  releaseResult: Record<string, unknown>;

  /** 数据确认阶段产物（含数据澄清项），后端按动作回流，前端只读展示 */
  dataConfirmation?: DataConfirmation;
  investigationMode?: string;
  autonomousRun?: AutonomousRun;

  createdAt: string;
  updatedAt: string;
}

/** 创建案例请求体（POST /api/diagnosis/cases） */
export interface CreateDiagnosisCaseInput {
  sessionId: string;
  ruleId: string;
  profileId: string;
  statStart: string;
  statEnd: string;
  modelId: string;
  caseInput?: Record<string, unknown>;
  expectedClassification?: Record<string, unknown>;
}

/** 动作请求体（POST /api/diagnosis/cases/{caseId}/actions） */
export interface DiagnosisActionRequest {
  action: string;
  payload: Record<string, unknown>;
}

/** 分子/分母明细行（结构不确定，按 key 渲染） */
export type DiagnosisDetailRow = Record<string, unknown>;

export type UploadedSqlMode = 'FILTER_SQL' | 'FULL_CANDIDATE_SQL';
export type UploadedSqlMembership = 'INCLUDE' | 'EXCLUDE';

export interface UploadedSqlRequest {
  mode: UploadedSqlMode;
  membership?: UploadedSqlMembership;
  sqlText: string;
  fileName?: string;
  targetNodeId?: string;
  confirmNewDependencies?: boolean;
}

export interface SqlImpactAnalysis {
  database: 'ORACLE' | 'SQL_SERVER';
  dialect: string;
  referencedTables: string[];
  outputFields: string[];
  matchKey: string;
  newDependencies: string[];
  requiresDependencyConfirmation: boolean;
  recommendedLayer: 'SOURCE_EXTRACT' | 'STATISTICS' | 'OVERVIEW' | 'AMBIGUOUS';
  affectedNodeIds: string[];
  ambiguous: boolean;
}

export interface UploadedSqlAnalysis {
  mode: UploadedSqlMode;
  membership: UploadedSqlMembership | '';
  sqlText: string;
  fileName: string;
  summary: string;
  impactAnalysis: SqlImpactAnalysis;
  validation: { ok: boolean; message: string };
  targetChoices: Array<{ nodeId: string; label: string }>;
}

export interface SqlRepairOptions {
  ruleId: string;
  profileId: string;
  recommendedLayer?: 'SOURCE_EXTRACT' | 'OVERVIEW';
  actions: string[];
  uploadModes: Array<{ mode: UploadedSqlMode; membership?: UploadedSqlMembership; tone: string }>;
  uploadExamples?: SqlUploadExample[];
  rules: Array<{ key: string; label: string; available: boolean }>;
  nodes: Array<{
    nodeId: string;
    label: string;
    sqlKind: string;
    database: string;
    dialect: string;
    available: boolean;
  }>;
}

export interface SqlUploadExample {
  mode: UploadedSqlMode;
  membership?: UploadedSqlMembership;
  title: string;
  sqlText: string;
  database: string;
  dialect: string;
  targetNodeId: string;
}

export type ShadowDiffType = 'ADDED' | 'REMOVED' | 'CHANGED' | 'DUPLICATE';

export interface ShadowDiffItem {
  businessKey: string;
  beforeRows: Array<Record<string, unknown>>;
  afterRows: Array<Record<string, unknown>>;
  changedFields: string[];
}

export interface ShadowDiffPage {
  trialId: string;
  type: ShadowDiffType;
  page: number;
  pageSize: number;
  total: number;
  items: ShadowDiffItem[];
}

/** 分子/分母明细响应（GET /api/diagnosis/cases/{caseId}/details） */
export interface DiagnosisDetailsResponse {
  batchRunId: string;
  ruleId: string;
  ruleName: string;
  group: DetailGroup;
  statStart: string;
  statEnd: string;
  page: number;
  pageSize: number;
  rowCount: number;
  rows: DiagnosisDetailRow[];
  truncated: boolean;
  snapshotId: string;
  snapshotReused: boolean;
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

/** 导出任务（createIndicatorExport / createDiagnosisReportExport 等返回） */
export interface IndicatorExport {
  exportId: string;
  fileName: string;
}

/** 数据初筛命中规则（GET /api/diagnosis/cases/{caseId}/data-screening） */
export interface DataScreeningRule {
  ruleId: string;
  target: string;
  changeLayer: string;
  action: string;
  sourcePath: string;
}

/** 数据初筛单条命中（疑似测试患者 / 测试或血液透析门诊科室 / 重复业务编号） */
export interface DataScreeningFinding {
  findingId: string;
  ruleCode: string;
  ruleSource: string;
  changeLayer: string;
  reason: string;
  rowKey: string;
  field?: string;
  value?: string;
  count?: number;
  sourceGroup?: 'NUMERATOR_DETAIL' | 'DENOMINATOR_DETAIL' | (string & {});
  target?: string;
  row: Record<string, unknown>;
}

/** 数据初筛响应（AI 初筛模块数据源） */
export interface DiagnosisDataScreening {
  caseId: string;
  ruleId: string;
  profileId: string;
  scannedRows: number;
  findingCount: number;
  truncated: boolean;
  rules: DataScreeningRule[];
  modelUsed: boolean;
  countsReconciled: boolean;
  overviewSqlHash: string;
  departmentOptions: Array<{
    field: string;
    value: string;
    label: string;
    denominatorCount: number;
    numeratorCount: number;
  }>;
  findings: DataScreeningFinding[];
}

/** 数据确认总览（SUBMIT_DATA_CONFIRMATION 动作写入） */
export interface DataConfirmationSummary {
  status: 'NO_ISSUE' | 'NEEDS_LINEAGE_REVIEW' | (string & {});
  overIncludedCount?: number;
  overIncludedNote?: string;
  underIncludedNote?: string;
  summary?: string;
  nextAction?: string;
}

/** 单个方向的澄清结果（CLARIFY_DATA_CONFIRMATION 动作写入，按方向分组） */
export interface ClarificationDirection {
  direction: string;
  status?:
    'IN_NUMERATOR' | 'IN_DENOMINATOR_ONLY' | 'NOT_IN_DETAIL' | 'NOT_IN_NUMERATOR' | (string & {});
  numeratorCount?: number;
  denominatorCount?: number;
  summary?: string;
  description?: string;
  requestedMissingScope?: string;
  naturalLanguageExplanation?: string;
  explanationSource?: string;
  explanationModel?: string;
  evidenceVerified?: boolean;
  targets?: Array<Record<string, unknown>>;
  targetResults?: Array<Record<string, unknown>>;
  /** 已核验的实际分子明细（后端按证据字段裁剪后回流，单对象时位于顶层） */
  numeratorEvidenceRows?: Array<Record<string, unknown>>;
  /** 未进入分子但进入分母时，回流实际分母明细 */
  denominatorEvidenceRows?: Array<Record<string, unknown>>;
  membershipVerdict?: string;
  firstMissingStage?: string;
  evidenceSections?: Array<{
    source: string;
    label: string;
    rows: Array<Record<string, unknown>>;
  }>;
  ruleEvaluations?: Array<Record<string, unknown>>;
  finalConclusion?: string;
  missingFields?: string[];
  sourceTables?: string[];
}

/** 数据确认阶段产物（只读展示，后端按动作回流） */
export interface DataConfirmation {
  clarification?: DataConfirmationSummary;
  clarifications?: Record<string, ClarificationDirection>;
  /** 数据确认固化的「数据多了」患者记录 */
  overIncludedRows?: Array<{
    rowKey: string;
    recordId: string;
    label: string;
    sourceGroup?: string;
  }>;
  /** 数据确认固化的「数据多了」科室范围 */
  overIncludedDepartments?: Array<{
    targetType: 'DEPARTMENT';
    field: string;
    values: string[];
    labels: string[];
    sourceGroup?: string;
  }>;
  /** 数据确认固化的「数据多了」补充说明 */
  overIncludedNote?: string;
  /** 数据确认固化的「数据少了」补充说明 */
  underIncludedNote?: string;
  /** 数据确认固化的「数据少了」缺失范围 */
  underIncludedTargets?: Array<Record<string, unknown>>;
  /** 数据确认固化的公共规则 ID */
  publicRuleIds?: string[];
  /** 数据确认提交时间（用于关联后续证据） */
  submittedAt?: string;
}

/** AI 生成 SQL 排除范围目标（scopeTargets 元素，对齐 IMPLEMENTER_SQL_REQUIREMENT 契约） */
export interface AiScopeTarget {
  /** 排除粒度：按业务记录（患者）或按科室 */
  targetType: 'RECORD' | 'DEPARTMENT';
  /** 后端匹配的字段名（如 ENCOUNTER_ID / CURRENT_DEPT_NAME） */
  field: string;
  /** 字段取值列表 */
  values: string[];
  /** 字段取值对应的展示标签 */
  labels: string[];
}

/** 按患者排除的候选对象（值 + 匹配字段 + 展示标签） */
export interface AiPatientOption {
  value: string;
  field: string;
  label: string;
}

export interface AutonomousRun extends Record<string, unknown> {
  conversationId?: string;
  status?: string;
  problem?: string;
  pendingQuestion?: string;
  finalConclusion?:
    | string
    | {
        conclusion?: string;
        conclusionLevel?: string;
        evidenceIds?: string[];
        candidateRequired?: boolean;
      };
  turns?: Array<Record<string, unknown>>;
  toolEvents?: Array<Record<string, unknown>>;
}

export interface AssistantConversationSummary {
  conversationId: string;
  type: 'PATIENT_CLARIFICATION' | 'AUTONOMOUS';
  title: string;
  status: string;
  preview: string;
  createdAt: string;
  updatedAt: string;
}

export interface AssistantConversation extends AssistantConversationSummary {
  messages: Array<Record<string, unknown>>;
  clarification?: ClarificationDirection;
  toolEvents?: Array<Record<string, unknown>>;
  autonomousRun?: AutonomousRun;
}

export interface AssistantConversationList {
  caseId: string;
  page: number;
  pageSize: number;
  total: number;
  items: AssistantConversationSummary[];
}

export interface AssistantIntentResult {
  intent: 'PATIENT_CLARIFICATION' | 'SQL_GENERATION' | 'UNKNOWN';
  target: 'PATIENT' | 'DEPARTMENT' | 'UNSPECIFIED';
  source: 'RULE' | 'MODEL' | 'FALLBACK';
  confidence: number;
}

export interface TroubleshootingCaseItem {
  problemNumber: string;
  tfsNumber: string;
  date: string;
  handler: string;
  category: string;
  profileId: string;
  ruleId: string;
  indicatorName: string;
  problemDescription: string;
  rootCause: string;
  solution: string;
  common: boolean;
}

export interface TroubleshootingCaseCategory {
  name: string;
  count: number;
  cases: TroubleshootingCaseItem[];
}

export interface TroubleshootingCasesResponse {
  ruleId: string;
  profileId: string;
  indicatorName: string;
  profileName: string;
  categories: TroubleshootingCaseCategory[];
}

export interface DiagnosisAgentEvents {
  caseId: string;
  events: Array<Record<string, unknown>>;
  status: string;
  autonomousRun: AutonomousRun;
  updatedAt: string;
}

export type PatientClarificationDirection = 'OVER_COUNTED' | 'UNDER_COUNTED';
export type PatientLookupMode = 'NAME_BED' | 'IMRN_ADMISSION_DATE' | 'ENCOUNTER_ID' | 'NAME_IMRN';

export interface PatientCandidateSearchInput {
  direction: PatientClarificationDirection;
  lookupMode: PatientLookupMode;
  fullName?: string;
  bedNo?: string;
  imrn?: string;
  admissionDate?: string;
  encounterId?: string;
  page?: number;
  pageSize?: number;
}

export interface PatientCandidate {
  encounterId: string;
  fullName: string;
  imrn: string;
  bedNo: string;
  admittedAt: string;
  departmentName: string;
  sourceLayer:
    'TARGET' | 'SOURCE_EXTRACTION' | 'BUSINESS_FALLBACK' | 'BUSINESS_DIRECT' | 'RECONCILED_DETAIL';
  targetPresent: boolean;
  denominatorPresent: boolean;
  numeratorPresent: boolean;
  membership: 'IN_NUMERATOR' | 'IN_DENOMINATOR' | 'IN_TARGET_ONLY' | 'BUSINESS_ONLY';
  row: DiagnosisDetailRow;
}

export interface PatientCandidateSearchResponse {
  caseId: string;
  ruleId: string;
  direction: PatientClarificationDirection;
  lookupMode: PatientLookupMode;
  page: number;
  pageSize: number;
  total: number;
  truncated: boolean;
  items: PatientCandidate[];
  targetTableAvailable: boolean;
  sourceLayer: string;
  statStart: string;
  statEnd: string;
  emptyReason: string;
  warning: string;
}
