/**
 * 指标异常排查领域常量（动作 / 步骤 / 关卡）
 *
 * 取值严格对齐后端状态机文档：
 * - winning-winex-mras-aima/docs/api/diagnosis-case-actions.md（动作矩阵）
 * - winning-winex-mras-aima/docs/diagnosis-troubleshooting-flow.md（步骤对照表）
 *
 * 本文件位于共享层：同时被 services / stores / ChatView / DiagnosisWorkspace 引用，
 * 避免「views 一级目录之间相互引用」。
 */

/** 16 个合法动作（ActionRequest.action 枚举白名单） */
export const DIAGNOSIS_ACTION = {
  CONFIRM_CALIBER: 'CONFIRM_CALIBER',
  RUN_BASE_CHECKS: 'RUN_BASE_CHECKS',
  RUN_GATE: 'RUN_GATE',
  RECHECK_GATE: 'RECHECK_GATE',
  SUBMIT_CASE: 'SUBMIT_CASE',
  /** 数据确认：提交共性筛查异常项（含数据多了/少了） */
  SUBMIT_DATA_CONFIRMATION: 'SUBMIT_DATA_CONFIRMATION',
  /** 数据确认：按方向（OVER_INCLUDED / UNDER_INCLUDED）逐项澄清 */
  CLARIFY_DATA_CONFIRMATION: 'CLARIFY_DATA_CONFIRMATION',
  /** 停止当前仍在执行的患者澄清，且不保存迟到结果 */
  CANCEL_DATA_CLARIFICATION: 'CANCEL_DATA_CLARIFICATION',
  CONFIRM_CASE_CALIBER: 'CONFIRM_CASE_CALIBER',
  SUBMIT_EVIDENCE: 'SUBMIT_EVIDENCE',
  CONFIRM_CAUSE: 'CONFIRM_CAUSE',
  CLOSE_AS_CORRECT: 'CLOSE_AS_CORRECT',
  BUILD_CANDIDATE: 'BUILD_CANDIDATE',
  RUN_SHADOW_TRIAL: 'RUN_SHADOW_TRIAL',
  REVISE_CANDIDATE: 'REVISE_CANDIDATE',
  SAVE_HOSPITAL_DRAFT: 'SAVE_HOSPITAL_DRAFT',
  REVALIDATE_HOSPITAL_DRAFT: 'REVALIDATE_HOSPITAL_DRAFT',
  /** 公共规则修复预览：生成候选 SQL */
  PREVIEW_PUBLIC_RULE_FIX: 'PREVIEW_PUBLIC_RULE_FIX',
  /** 公共规则修复执行：影子试跑候选 SQL */
  RUN_PUBLIC_RULE_FIX: 'RUN_PUBLIC_RULE_FIX',
  /** 数据链路：使用当前正式 SQL 重新抽取（源表）或重新统计（概览）并计算 */
  RUN_LINEAGE_BASELINE: 'RUN_LINEAGE_BASELINE',
  START_AUTONOMOUS_INVESTIGATION: 'START_AUTONOMOUS_INVESTIGATION',
  SEND_AUTONOMOUS_MESSAGE: 'SEND_AUTONOMOUS_MESSAGE',
  RESPOND_AUTONOMOUS_QUESTION: 'RESPOND_AUTONOMOUS_QUESTION',
  CANCEL_AUTONOMOUS_INVESTIGATION: 'CANCEL_AUTONOMOUS_INVESTIGATION',
} as const;

export const DIAGNOSIS_ASSISTANT_ACTION = {
  PATIENT_CLARIFICATION: 'PATIENT_CLARIFICATION',
  AI_GENERATE_SQL: 'AI_GENERATE_SQL',
  UPLOAD_SQL: 'UPLOAD_SQL',
  /** @deprecated 仅兼容旧案例和旧接口，新界面不再单独展示。 */
  EXCLUDE_DEPARTMENT: 'EXCLUDE_DEPARTMENT',
  /** @deprecated 仅兼容旧案例和旧接口，新界面不再单独展示。 */
  EXCLUDE_PATIENT: 'EXCLUDE_PATIENT',
  AUTONOMOUS: 'AUTONOMOUS',
} as const;

export const PATIENT_CLARIFICATION_DIRECTION = {
  OVER_COUNTED: 'OVER_COUNTED',
  UNDER_COUNTED: 'UNDER_COUNTED',
} as const;

export const DATA_CLARIFICATION_DIRECTION = {
  OVER_INCLUDED: 'OVER_INCLUDED',
  UNDER_INCLUDED: 'UNDER_INCLUDED',
} as const;

export const PATIENT_LOOKUP_MODE = {
  NAME_BED: 'NAME_BED',
  IMRN_ADMISSION_DATE: 'IMRN_ADMISSION_DATE',
  ENCOUNTER_ID: 'ENCOUNTER_ID',
  NAME_IMRN: 'NAME_IMRN',
} as const;

export const ASSISTANT_CONVERSATION_TYPE = {
  PATIENT_CLARIFICATION: 'PATIENT_CLARIFICATION',
  AUTONOMOUS: 'AUTONOMOUS',
} as const;

export const AUTONOMOUS_STATUS = {
  RUNNING: 'RUNNING',
  QUEUED: 'QUEUED',
  WAITING_USER: 'WAITING_USER',
  READY: 'READY',
  COMPLETED: 'COMPLETED',
  STOPPED: 'STOPPED',
  CANCELLED: 'CANCELLED',
  FAILED: 'FAILED',
} as const;

/** 动作名称联合类型（用于收紧 submitDiagnosisAction 参数，编译期防误传） */
export type DiagnosisActionName = (typeof DIAGNOSIS_ACTION)[keyof typeof DIAGNOSIS_ACTION];

/** 步骤（currentStep）枚举 */
export const DIAGNOSIS_STEP = {
  CALIBER_CONFIRMATION: 'CALIBER_CONFIRMATION',
  GATE_1_SCHEMA: 'GATE_1_SCHEMA',
  GATE_2_EVENT: 'GATE_2_EVENT',
  GATE_3_VALUE: 'GATE_3_VALUE',
  CASE_INPUT: 'CASE_INPUT',
  CASE_CALIBER_CLARIFICATION: 'CASE_CALIBER_CLARIFICATION',
  CASE_INVESTIGATION: 'CASE_INVESTIGATION',
  CHANGE_PROPOSAL: 'CHANGE_PROPOSAL',
  SHADOW_TRIAL: 'SHADOW_TRIAL',
  DRAFT_SAVE: 'DRAFT_SAVE',
  COMPLETED: 'COMPLETED',
  WAITING_EXTERNAL_FIX: 'WAITING_EXTERNAL_FIX',
} as const;

/** 三关编号（GateResult.gate） */
export const GATE = {
  SCHEMA: 1,
  EVENT: 2,
  VALUE: 3,
} as const;

export const ATTAINMENT_LABEL = {
  MET: '达标',
  NOT_MET: '未达标',
  PENDING: '待判定',
} as const;

export const TARGET_DIRECTION_SYMBOL: Record<string, string> = {
  '>': '>',
  gt: '>',
  '>=': '≥',
  '≥': '≥',
  up: '≥',
  gte: '≥',
  '<': '<',
  lt: '<',
  '<=': '≤',
  '≤': '≤',
  down: '≤',
  lte: '≤',
};

/** 步骤顺序（用于时间线可视化） */
export const DIAGNOSIS_STEP_ORDER = [
  DIAGNOSIS_STEP.CALIBER_CONFIRMATION,
  DIAGNOSIS_STEP.GATE_1_SCHEMA,
  DIAGNOSIS_STEP.GATE_2_EVENT,
  DIAGNOSIS_STEP.GATE_3_VALUE,
  DIAGNOSIS_STEP.CASE_INPUT,
  DIAGNOSIS_STEP.CASE_CALIBER_CLARIFICATION,
  DIAGNOSIS_STEP.CASE_INVESTIGATION,
  DIAGNOSIS_STEP.CHANGE_PROPOSAL,
  DIAGNOSIS_STEP.SHADOW_TRIAL,
  DIAGNOSIS_STEP.DRAFT_SAVE,
  DIAGNOSIS_STEP.COMPLETED,
] as const;

/** 步骤中文标签 */
export const DIAGNOSIS_STEP_LABELS: Record<string, string> = {
  CALIBER_CONFIRMATION: '确认口径',
  GATE_1_SCHEMA: '数据结构校验',
  GATE_2_EVENT: '事件配置校验',
  GATE_3_VALUE: '现场数值校验',
  CASE_INPUT: '提交记录信息',
  CASE_CALIBER_CLARIFICATION: '确认案例口径',
  CASE_INVESTIGATION: '案例查因',
  CHANGE_PROPOSAL: '拟定修改方案',
  SHADOW_TRIAL: '影子试跑',
  DRAFT_SAVE: '保存医院草稿',
  COMPLETED: '排查完成',
  WAITING_EXTERNAL_FIX: '等待院方修复',
};

/** 步骤 Material 图标 */
export const DIAGNOSIS_STEP_ICONS: Record<string, string> = {
  CALIBER_CONFIRMATION: 'mdi-file-document-check-outline',
  GATE_1_SCHEMA: 'mdi-table-cog',
  GATE_2_EVENT: 'mdi-calendar-check-outline',
  GATE_3_VALUE: 'mdi-counter',
  CASE_INPUT: 'mdi-form-textbox',
  CASE_CALIBER_CLARIFICATION: 'mdi-file-compare',
  CASE_INVESTIGATION: 'mdi-magnify-scan',
  CHANGE_PROPOSAL: 'mdi-file-edit-outline',
  SHADOW_TRIAL: 'mdi-test-tube-off',
  DRAFT_SAVE: 'mdi-content-save-outline',
  COMPLETED: 'mdi-check-circle-outline',
  WAITING_EXTERNAL_FIX: 'mdi-clock-outline',
};

/** 业务状态语义色（用于状态 chip），对齐数据结构文档 §1.2 status 全集 */
export const STEP_STATUS_COLOR: Record<string, string> = {
  WAITING_CALIBER_CONFIRMATION: 'warning',
  IN_PROGRESS: 'primary',
  GATES_PASSED: 'success',
  WAITING_CASE_CALIBER_CONFIRMATION: 'warning',
  CAUSE_CONFIRMED: 'info',
  CANDIDATE_READY: 'info',
  WAITING_EXTERNAL_FIX: 'warning',
  SHADOW_PASSED: 'success',
  SHADOW_FAILED: 'error',
  CANDIDATE_FAILED: 'warning',
  COMPLETED: 'success',
  DRAFT_SAVED: 'success',
  DRAFT_BASELINE_EXPIRED: 'warning',
  DRAFT_REVALIDATED: 'success',
  DRAFT_REVALIDATION_FAILED: 'error',
};
