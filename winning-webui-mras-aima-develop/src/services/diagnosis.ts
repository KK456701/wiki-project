/**
 * 指标异常排查（诊断案例）API 封装
 *
 * 统一经由 `@/utils/request` 的 request() 发起，禁止裸写 fetch（B10）。
 * 路径前缀由 request() 自动补全为 `${API_BASE_PREFIX}/api`。
 */
import { request } from '@/utils/request';
import type {
  DiagnosisCaseSnapshot,
  CreateDiagnosisCaseInput,
  DiagnosisDetailsResponse,
  DiagnosisDataScreening,
  AssistantConversation,
  AssistantConversationList,
  AssistantIntentResult,
  DiagnosisAgentEvents,
  PatientCandidateSearchInput,
  PatientCandidateSearchResponse,
  TroubleshootingCasesResponse,
  SqlRepairOptions,
  UploadedSqlAnalysis,
  UploadedSqlRequest,
  ShadowDiffPage,
  ShadowDiffType,
} from '@/types/diagnosis';
import type { DetailGroup } from '@/types/chat';
import type { DiagnosisActionName } from '@/constants/diagnosis';

/**
 * 排查动作接口超时（毫秒）。
 *
 * `/actions` 背后跑口径确认 + 三关校验（重算当前口径），属后端长任务，常超过默认 30s。
 * 放宽到 3 分钟，避免客户端 AbortController 误判为 canceled（request.ts 默认 30000）。
 * 传 <=0 表示不超时；如需更短可在调用处覆盖 `options.timeout`。
 */
const DIAGNOSIS_ACTION_TIMEOUT_MS = 180000;
const DIAGNOSIS_DETAIL_TIMEOUT_MS = 180000;

/** 明细加载专属错误（携带后端 code，便于前端差异化提示） */
export class DiagnosisDetailError extends Error {
  code?: string;
  constructor(message: string, code?: string) {
    super(message);
    this.name = 'DiagnosisDetailError';
    this.code = code;
  }
}

/** 动作提交专属错误（携带后端 code，便于前端差异化处理，例如步骤顺序冲突时自动重同步） */
export class DiagnosisActionError extends Error {
  code?: string;
  constructor(message: string, code?: string) {
    super(message);
    this.name = 'DiagnosisActionError';
    this.code = code;
  }
}

/** 将后端 409 的 code 映射为可引导用户的友好文案 */
function mapDetailErrorMessage(code: string | undefined, backendMsg?: string): string {
  if (code === 'DIAGNOSIS_DETAIL_CONTEXT_MISSING') {
    return '请先确认口径并跑通第 2 关（事件配置校验）后再查看明细。';
  }
  if (code === 'DIAGNOSIS_DETAIL_CONTRACT_CHANGED') {
    return '知识库口径已变更，请重新跑通基础校验后再查看明细。';
  }
  return backendMsg || '当前无法加载明细，请稍后重试。';
}

/**
 * 从错误响应体统一提取 { message, code }
 *
 * 兼容后端三种错误结构（见 api/README.md §5）：
 * - `{ detail: "文本" }`
 * - `{ detail: { code, message } }`   ← DIAGNOSIS_STEP_ORDER_VIOLATION 等结构化错误
 * - 顶层 `code` / `message`
 */
async function extractErrorDetail(res: Response): Promise<{ message: string; code?: string }> {
  try {
    const body = (await res.json()) as {
      detail?: string | { code?: string; message?: string };
      message?: string;
      code?: string;
    };
    if (body.detail) {
      if (typeof body.detail === 'object') {
        return {
          message: body.detail.message || body.detail.code || `请求失败 (${res.status})`,
          code: body.detail.code,
        };
      }
      return { message: body.detail };
    }
    if (body.message) return { message: body.message, code: body.code };
    if (body.code) return { message: body.code, code: body.code };
  } catch {
    // 响应体非 JSON，忽略解析，回退到状态码
  }
  return { message: `请求失败 (${res.status})` };
}

/** 仅取可读错误信息（供不区分 code 的接口复用同一解析逻辑） */
async function parseApiError(res: Response): Promise<string> {
  return (await extractErrorDetail(res)).message;
}

/**
 * 创建排查案例
 * POST /api/diagnosis/cases
 */
export async function createDiagnosisCase(
  input: CreateDiagnosisCaseInput,
): Promise<DiagnosisCaseSnapshot> {
  const res = await request('/diagnosis/cases', {
    method: 'POST',
    body: JSON.stringify(input),
  });
  if (!res.ok) {
    throw new Error(await parseApiError(res));
  }
  return (await res.json()) as DiagnosisCaseSnapshot;
}

/**
 * 读取案例快照（单一数据源）
 * GET /api/diagnosis/cases/{caseId}
 */
export async function getDiagnosisCase(caseId: string): Promise<DiagnosisCaseSnapshot> {
  const res = await request(`/diagnosis/cases/${caseId}`);
  if (!res.ok) {
    throw new Error(await parseApiError(res));
  }
  return (await res.json()) as DiagnosisCaseSnapshot;
}

/**
 * 提交排查动作，返回刷新后的快照
 * POST /api/diagnosis/cases/{caseId}/actions
 *
 * @param timeout 覆盖默认超时（毫秒）。默认 {@link DIAGNOSIS_ACTION_TIMEOUT_MS}，
 *                因该接口为后端长任务（口径确认 + 三关校验），可超过 30s。
 */
export async function submitDiagnosisAction(
  caseId: string,
  action: DiagnosisActionName,
  payload: Record<string, unknown> = {},
  options: { timeout?: number; signal?: AbortSignal } = {},
): Promise<DiagnosisCaseSnapshot> {
  const res = await request(`/diagnosis/cases/${caseId}/actions`, {
    method: 'POST',
    body: JSON.stringify({ action, payload }),
    timeout: options.timeout ?? DIAGNOSIS_ACTION_TIMEOUT_MS,
    signal: options.signal,
  });
  if (!res.ok) {
    const { message, code } = await extractErrorDetail(res);
    throw new DiagnosisActionError(message, code);
  }
  return (await res.json()) as DiagnosisCaseSnapshot;
}

/**
 * 读取分子/分母明细
 * GET /api/diagnosis/cases/{caseId}/details
 *
 * 对 409 做语义分支：后端在「未先跑基础校验」时返回
 * DIAGNOSIS_DETAIL_CONTEXT_MISSING，在「知识库口径变更」时返回
 * DIAGNOSIS_DETAIL_CONTRACT_CHANGED，均抛出 DiagnosisDetailError 以便前端引导用户。
 */
export async function getDiagnosisDetails(
  caseId: string,
  group: DetailGroup | undefined,
  page = 1,
  pageSize = 50,
  filters: { search?: string; department?: string } = {},
): Promise<DiagnosisDetailsResponse> {
  const params = new URLSearchParams({ page: String(page), pageSize: String(pageSize) });
  if (group) params.set('group', group);
  if (filters.search) params.set('search', filters.search);
  if (filters.department) params.set('department', filters.department);
  const res = await request(`/diagnosis/cases/${caseId}/details?${params.toString()}`, {
    timeout: DIAGNOSIS_DETAIL_TIMEOUT_MS,
  });
  if (res.status === 409) {
    const { message, code } = await extractErrorDetail(res);
    throw new DiagnosisDetailError(mapDetailErrorMessage(code, message), code);
  }
  if (!res.ok) {
    throw new Error(await parseApiError(res));
  }
  return (await res.json()) as DiagnosisDetailsResponse;
}

export async function getAssistantConversations(
  caseId: string,
  page = 1,
  pageSize = 20,
): Promise<AssistantConversationList> {
  const params = new URLSearchParams({ page: String(page), pageSize: String(pageSize) });
  const res = await request(`/diagnosis/cases/${caseId}/assistant-conversations?${params}`);
  if (!res.ok) throw new Error(await parseApiError(res));
  return (await res.json()) as AssistantConversationList;
}

export async function getAssistantConversation(
  caseId: string,
  conversationId: string,
): Promise<AssistantConversation> {
  const res = await request(`/diagnosis/cases/${caseId}/assistant-conversations/${conversationId}`);
  if (!res.ok) throw new Error(await parseApiError(res));
  return (await res.json()) as AssistantConversation;
}

export async function getTroubleshootingCases(
  caseId: string,
): Promise<TroubleshootingCasesResponse> {
  const res = await request(`/diagnosis/cases/${caseId}/troubleshooting-cases`);
  if (!res.ok) throw new Error(await parseApiError(res));
  return (await res.json()) as TroubleshootingCasesResponse;
}

export async function classifyAssistantIntent(
  caseId: string,
  message: string,
): Promise<AssistantIntentResult> {
  const res = await request(`/diagnosis/cases/${caseId}/assistant-intent`, {
    method: 'POST',
    body: JSON.stringify({ message }),
    timeout: 25000,
  });
  if (!res.ok) throw new Error(await parseApiError(res));
  return (await res.json()) as AssistantIntentResult;
}

export async function getDiagnosisAgentEvents(
  caseId: string,
  afterSeq = 0,
): Promise<DiagnosisAgentEvents> {
  const res = await request(`/diagnosis/cases/${caseId}/agent-events?afterSeq=${afterSeq}`);
  if (!res.ok) throw new Error(await parseApiError(res));
  return (await res.json()) as DiagnosisAgentEvents;
}

/**
 * 数据初筛（AI 初筛模块数据源）
 * GET /api/diagnosis/cases/{caseId}/data-screening
 */
export async function getDiagnosisDataScreening(caseId: string): Promise<DiagnosisDataScreening> {
  const res = await request(`/diagnosis/cases/${caseId}/data-screening`, {
    timeout: DIAGNOSIS_DETAIL_TIMEOUT_MS,
  });
  if (!res.ok) {
    throw new Error(await parseApiError(res));
  }
  return (await res.json()) as DiagnosisDataScreening;
}

export async function searchDiagnosisPatientCandidates(
  caseId: string,
  input: PatientCandidateSearchInput,
  signal?: AbortSignal,
): Promise<PatientCandidateSearchResponse> {
  const res = await request(`/diagnosis/cases/${caseId}/patient-candidates/search`, {
    method: 'POST',
    body: JSON.stringify(input),
    timeout: DIAGNOSIS_DETAIL_TIMEOUT_MS,
    signal,
  });
  if (!res.ok) throw new Error(await parseApiError(res));
  return (await res.json()) as PatientCandidateSearchResponse;
}

export async function getSqlRepairOptions(caseId: string): Promise<SqlRepairOptions> {
  const res = await request(`/diagnosis/cases/${caseId}/sql-repair-options`);
  if (!res.ok) throw new Error(await parseApiError(res));
  return (await res.json()) as SqlRepairOptions;
}

export async function getDiagnosisShadowDiffs(
  caseId: string,
  trialId: string,
  type: ShadowDiffType,
  page = 1,
  pageSize = 20,
  search = '',
): Promise<ShadowDiffPage> {
  const params = new URLSearchParams({
    trialId,
    type,
    page: String(page),
    pageSize: String(pageSize),
  });
  if (search.trim()) params.set('search', search.trim());
  const res = await request(`/diagnosis/cases/${caseId}/shadow-diffs?${params.toString()}`);
  if (!res.ok) throw new Error(await parseApiError(res));
  return (await res.json()) as ShadowDiffPage;
}

export async function analyzeUploadedSql(
  caseId: string,
  input: UploadedSqlRequest,
): Promise<UploadedSqlAnalysis> {
  const res = await request(`/diagnosis/cases/${caseId}/uploaded-sql/analyze`, {
    method: 'POST',
    body: JSON.stringify(input),
    timeout: DIAGNOSIS_DETAIL_TIMEOUT_MS,
  });
  if (!res.ok) throw new Error(await parseApiError(res));
  return (await res.json()) as UploadedSqlAnalysis;
}

export async function createCandidateChangeSet(
  caseId: string,
  input: UploadedSqlRequest,
): Promise<DiagnosisCaseSnapshot> {
  const res = await request(`/diagnosis/cases/${caseId}/candidate-change-sets`, {
    method: 'POST',
    body: JSON.stringify(input),
    timeout: DIAGNOSIS_DETAIL_TIMEOUT_MS,
  });
  if (!res.ok) throw new Error(await parseApiError(res));
  return (await res.json()) as DiagnosisCaseSnapshot;
}
