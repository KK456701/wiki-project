import { request } from '@/utils/request';

export const BATCH_ANALYSIS_ACTION = {
  CONFIRMATION_CHECKLIST: 'batch_confirmation_checklist',
  DATA_QUALITY_REVIEW: 'batch_data_quality_review',
} as const;

export type BatchAnalysisAction =
  (typeof BATCH_ANALYSIS_ACTION)[keyof typeof BATCH_ANALYSIS_ACTION];

interface AnalysisResponse {
  action: string;
  auditId: string;
  answer: string;
}

interface InspectIndicatorInput {
  batchRunId: string;
  indicatorId: string;
  profileId?: string;
  modelId: string;
}

async function responseError(response: Response): Promise<string> {
  try {
    const body = (await response.json()) as {
      detail?: string | { message?: string };
      message?: string;
    };
    if (typeof body.detail === 'string') return body.detail;
    if (body.detail?.message) return body.detail.message;
    if (body.message) return body.message;
  } catch {
    // 非 JSON 错误响应统一回退到 HTTP 状态码。
  }
  return `请求失败 (${response.status})`;
}

export async function analyzeBatch(
  batchRunId: string,
  action: BatchAnalysisAction,
): Promise<AnalysisResponse> {
  const response = await request('/agent/actions/analyze-batch', {
    method: 'POST',
    body: JSON.stringify({ action, batchRunId }),
    timeout: 90_000,
  });
  if (!response.ok) throw new Error(await responseError(response));
  return (await response.json()) as AnalysisResponse;
}

export async function inspectIndicator(input: InspectIndicatorInput): Promise<AnalysisResponse> {
  const response = await request('/agent/actions/inspect-indicator', {
    method: 'POST',
    body: JSON.stringify({ action: 'inspect_indicator', ...input }),
    timeout: 90_000,
  });
  if (!response.ok) throw new Error(await responseError(response));
  return (await response.json()) as AnalysisResponse;
}
