import type {
  AgentCapabilities,
  BatchReportSnapshot,
  ChatRequest,
  ConnectionTestResult,
  EffectiveRule,
  MessageResponse,
  RuleDetailPage,
  RuleDetailQuery,
  RuleEffectiveQuery,
  RuleProfile,
  RuntimeConnectionSaveInput,
  RuntimeConnectionTestInput,
  RuntimeModelConfigInput,
  RuntimeSettings,
  SessionResponse,
  UploadResponse,
  TraceDetailResponse,
} from '@/types/chat';
import type { BatchRunResponse, ReportDownloadFormat } from '@/types/chat';
import { request } from '@/utils/request';
import { fetchSseStream, type SseCallbacks } from '@/utils/sse';

/** 规则列表项 */
export interface RuleItem {
  ruleId: string;
  ruleName: string;
}

/**
 * 获取规则列表
 */
export async function getRulesList(): Promise<RuleItem[]> {
  const response = await request('/kb/rules/list');
  if (!response.ok) {
    throw new Error(`获取规则列表失败: ${response.status}`);
  }
  return response.json();
}

/**
 * 获取指标口径列表
 * GET /api/kb/rules/{ruleId}/profiles
 */
export async function getRuleProfiles(ruleId: string): Promise<RuleProfile[]> {
  const response = await request(`/kb/rules/${ruleId}/profiles`);
  if (!response.ok) {
    throw new Error(`获取口径列表失败: ${response.status}`);
  }
  return response.json();
}

export type { ChatRequest };

/**
 * 获取 Agent 能力信息
 */
export async function getAgentCapabilities(): Promise<AgentCapabilities> {
  const response = await request('/agent/capabilities');
  if (!response.ok) {
    throw new Error(`获取 Agent 能力失败: ${response.status}`);
  }
  return response.json();
}

/**
 * 发送流式聊天请求（SSE）
 * @param chatRequest 聊天请求参数
 * @param callbacks SSE 事件回调
 * @returns AbortController
 */
export function sendChatStream(chatRequest: ChatRequest, callbacks: SseCallbacks): AbortController {
  return fetchSseStream(
    '/agent/chat/stream',
    chatRequest as unknown as Record<string, unknown>,
    callbacks,
  );
}

/**
 * 获取链路详情
 * @param traceId 链路追踪 ID
 * @returns 链路详情响应
 */
export async function getTraceDetail(traceId: string): Promise<TraceDetailResponse> {
  const response = await request(`/agent/runs/${traceId}`);
  if (!response.ok) {
    throw new Error(`获取链路详情失败: ${response.status}`);
  }
  return response.json();
}

/**
 * 获取批量运行详情（含 traceId）
 * @param batchRunId 批量作业 ID
 * @returns 批量运行响应
 */
export async function getBatchDetail(batchRunId: string): Promise<BatchRunResponse> {
  const response = await request(`/agent/batches/${batchRunId}`);
  if (!response.ok) {
    throw new Error(`获取批量运行详情失败: ${response.status}`);
  }
  return response.json();
}

/**
 * 获取对话列表
 */
export async function getSessions(): Promise<SessionResponse[]> {
  const response = await request('/agent/sessions');
  if (!response.ok) {
    throw new Error(`获取对话列表失败: ${response.status}`);
  }
  return response.json();
}

/**
 * 获取对话消息列表
 * @param sessionId 对话 ID
 */
export async function getSessionMessages(sessionId: string): Promise<MessageResponse[]> {
  const response = await request(`/agent/sessions/${sessionId}/messages`);
  if (!response.ok) {
    throw new Error(`获取对话消息失败: ${response.status}`);
  }
  return response.json();
}

/**
 * 创建新会话
 */
export async function createSession(): Promise<{ sessionId: string }> {
  const response = await request('/agent/sessions', { method: 'POST' });
  if (!response.ok) {
    throw new Error(`创建会话失败: ${response.status}`);
  }
  return response.json();
}

/**
 * 删除对话
 * @param sessionId 对话 ID
 */
export async function deleteSession(sessionId: string): Promise<{ message: string }> {
  const response = await request(`/agent/sessions/${sessionId}`, {
    method: 'DELETE',
  });
  if (!response.ok) {
    throw new Error(`删除对话失败: ${response.status}`);
  }
  return response.json();
}

/**
 * 上传文件
 * @param file 文件对象
 * @returns 上传响应（含 file_key）
 */
export async function uploadFile(file: File): Promise<UploadResponse> {
  const formData = new FormData();
  formData.append('file', file);

  const response = await request('/agent/upload', {
    method: 'POST',
    // 显式清空 Content-Type：让浏览器为 FormData 自动设置 multipart/form-data 边界
    headers: { 'Content-Type': '' },
    body: formData,
  });

  if (!response.ok) {
    throw new Error(`文件上传失败: ${response.status}`);
  }
  return response.json();
}

// ============ 批次报告 API ============

/**
 * 创建报告快照
 * POST /api/batch-runs/{batchRunId}/reports
 * @param batchRunId 批量作业 ID
 * @returns 报告快照
 */
export async function createReportSnapshot(batchRunId: string): Promise<BatchReportSnapshot> {
  const response = await request(`/batch-runs/${batchRunId}/reports`, {
    method: 'POST',
  });
  if (!response.ok) {
    if (response.status === 409) {
      throw new Error('批次尚未完成，请等待所有指标计算结束后再试');
    }
    if (response.status === 404) {
      throw new Error('批次不存在或无权访问');
    }
    throw new Error(`创建报告快照失败: ${response.status}`);
  }
  return response.json();
}

/**
 * 加载已有的报告快照
 * GET /api/batch-reports/{reportId}
 * @param reportId 报告唯一标识
 * @returns 报告快照
 */
export async function getReportSnapshot(reportId: string): Promise<BatchReportSnapshot> {
  const response = await request(`/batch-reports/${reportId}`);
  if (!response.ok) {
    if (response.status === 404) {
      throw new Error('报告不存在或无权访问');
    }
    throw new Error(`加载报告快照失败: ${response.status}`);
  }
  return response.json();
}

/**
 * 下载报告文件
 * GET /api/batch-reports/{reportId}/download?format={format}
 *
 * 触发浏览器文件下载，文件名从 Content-Disposition 头提取。
 * 需要用户具备 indicator_detail_export 权限。
 *
 * @param reportId 报告唯一标识
 * @param format 导出格式
 */
export async function downloadReport(
  reportId: string,
  format: ReportDownloadFormat,
): Promise<void> {
  const response = await request(`/batch-reports/${reportId}/download?format=${format}`, {
    method: 'GET',
  });

  if (!response.ok) {
    if (response.status === 400) {
      throw new Error(`不支持的导出格式: ${format}`);
    }
    if (response.status === 403) {
      throw new Error('当前账号没有报告下载权限，请联系管理员');
    }
    if (response.status === 404) {
      throw new Error('报告不存在或无权访问');
    }
    throw new Error(`下载报告失败: ${response.status}`);
  }

  // 创建 Blob 并触发浏览器下载
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;

  // 优先使用 Content-Disposition 头中的文件名
  const disposition = response.headers.get('Content-Disposition');
  const filenameMatch = disposition?.match(/filename\*=UTF-8''(.+)/);
  anchor.download = filenameMatch?.[1]
    ? decodeURIComponent(filenameMatch[1])
    : `核心指标报告_${reportId}.${format}`;

  anchor.style.display = 'none';
  document.body.appendChild(anchor);
  anchor.click();
  document.body.removeChild(anchor);
  URL.revokeObjectURL(url);
}

// ============ 指标规则 API ============

/**
 * 查询指标生效规则（口径/数据链路信息）
 * GET /api/kb/rules/{ruleId}/effective
 */
export async function getRuleEffective(query: RuleEffectiveQuery): Promise<EffectiveRule> {
  const params = new URLSearchParams();
  if (query.profileId) params.set('profileId', query.profileId);
  params.set('statStart', query.statStart);
  params.set('statEnd', query.statEnd);

  const response = await request(`/kb/rules/${query.ruleId}/effective?${params.toString()}`);
  if (!response.ok) {
    if (response.status === 404) {
      throw new Error('指标规则不存在');
    }
    throw new Error(`查询指标生效规则失败: ${response.status}`);
  }
  return response.json();
}

// ============ 运行时设置 API ============

/**
 * 获取运行时设置
 * GET /api/settings/runtime
 */
export async function getRuntimeSettings(): Promise<RuntimeSettings> {
  const response = await request('/settings/runtime');
  if (!response.ok) {
    throw new Error(`获取运行时设置失败: ${response.status}`);
  }
  return response.json();
}

/**
 * 测试数据库连接
 * POST /api/settings/connections/{connectionId}/test
 */
export async function testRuntimeConnection(
  connectionId: string,
  input?: RuntimeConnectionTestInput,
): Promise<ConnectionTestResult> {
  const response = await request(`/settings/connections/${encodeURIComponent(connectionId)}/test`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input || {}),
  });
  if (!response.ok) {
    throw new Error(`连接测试失败: ${response.status}`);
  }
  return response.json();
}

/**
 * 设置默认模型
 * POST /api/settings/models/default
 */
export async function setRuntimeDefaultModel(
  modelId: string,
): Promise<{ defaultModel: string; message: string }> {
  const response = await request('/settings/models/default', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ modelId }),
  });
  if (!response.ok) {
    throw new Error(`设置默认模型失败: ${response.status}`);
  }
  return response.json();
}

/**
 * 保存模型配置
 * POST /api/settings/models/configuration
 */
export async function saveRuntimeModelConfiguration(input: {
  defaultModel: string;
  models: RuntimeModelConfigInput[];
}): Promise<{
  defaultModel: string;
  models: import('@/types/chat').RuntimeModelSetting[];
  message: string;
}> {
  const response = await request('/settings/models/configuration', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
  if (!response.ok) {
    throw new Error(`保存模型配置失败: ${response.status}`);
  }
  return response.json();
}

/**
 * 保存数据库连接配置
 * POST /api/settings/connections/{connectionId}/save
 */
export async function saveRuntimeConnection(
  connectionId: string,
  input: RuntimeConnectionSaveInput,
): Promise<{
  connectionId: string;
  configured: boolean;
  restartRequired: boolean;
  message: string;
}> {
  const response = await request(`/settings/connections/${encodeURIComponent(connectionId)}/save`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
  if (!response.ok) {
    throw new Error(`保存连接配置失败: ${response.status}`);
  }
  return response.json();
}

/**
 * 查询指标明细数据
 * GET /api/kb/rules/{ruleId}/details
 */
export async function getRuleDetails(query: RuleDetailQuery): Promise<RuleDetailPage> {
  const params = new URLSearchParams();
  params.set('batchRunId', query.batchRunId);
  if (query.group) params.set('group', query.group);
  if (query.start) params.set('start', query.start);
  if (query.end) params.set('end', query.end);
  if (query.profileId) params.set('profileId', query.profileId);
  if (query.page != null) params.set('page', String(query.page));
  if (query.pageSize != null) params.set('pageSize', String(query.pageSize));

  const response = await request(`/kb/rules/${query.ruleId}/details?${params.toString()}`);
  if (!response.ok) {
    if (response.status === 404) {
      throw new Error('指标暂不支持明细查询');
    }
    throw new Error(`查询指标明细失败: ${response.status}`);
  }
  return response.json();
}
