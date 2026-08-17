/**
 * 数据导出 API 封装（指标明细 / 上传对比 / 诊断报告）
 *
 * 统一经由 `@/utils/request` 的 request() 发起，禁止裸写 fetch（遵循本项目 B10 规范）。
 * 端点对齐 readonly 参考实现（同一后端，已核实）：
 * - 明细导出   POST /api/sql-runs/{runId}/exports
 * - 上传对比   POST /api/sql-runs/{runId}/upload-comparison-exports
 * - 诊断报告   POST /api/diagnosis-reports/{reportId}/exports
 * - 文件下载   GET  /api/indicator-exports/{exportId}/download
 *
 * 三种导出均先创建导出任务（拿到 exportId + fileName），再以下载接口取回 blob 触发浏览器下载。
 *
 * ## 部署反向代理说明
 * 上述相对路径经 request() 统一补全为 `/wiki-agent/api/...`（见 utils/request.ts 的 API_BASE）。
 * 开发态由 vite.config.ts 的 server.proxy 将 `/wiki-agent` 转发至后端（rewrite 去掉前缀后即为 `/api/...`，
 * 与 readonly 参考实现一致）；生产部署时只需保证反向代理同样转发 `/wiki-agent` 前缀即可，无需为导出接口单独配置路由。
 */
import { request } from '@/utils/request';
import type { IndicatorExport } from '@/types/diagnosis';

/** 创建明细导出任务 */
export async function createIndicatorExport(
  runId: string,
  confirmed = true,
): Promise<IndicatorExport> {
  const res = await request(`/sql-runs/${encodeURIComponent(runId)}/exports`, {
    method: 'POST',
    body: JSON.stringify({ confirmed }),
  });
  if (!res.ok) {
    throw new Error(await parseExportError(res));
  }
  return (await res.json()) as IndicatorExport;
}

/** 从批次指标冻结快照创建整套明细工作簿。 */
export async function createBatchDetailExport(
  batchRunId: string,
  ruleId: string,
  profileId: string | null | undefined,
  confirmed = true,
): Promise<IndicatorExport> {
  const res = await request(
    `/batch-runs/${encodeURIComponent(batchRunId)}/rules/${encodeURIComponent(ruleId)}/detail-exports`,
    {
      method: 'POST',
      body: JSON.stringify({ confirmed, profileId: profileId || null }),
    },
  );
  if (!res.ok) throw new Error(await parseExportError(res));
  return (await res.json()) as IndicatorExport;
}

/** 从异常排查案例冻结快照创建整套明细工作簿。 */
export async function createDiagnosisDetailExport(
  caseId: string,
  confirmed = true,
): Promise<IndicatorExport> {
  const res = await request(`/diagnosis/cases/${encodeURIComponent(caseId)}/detail-exports`, {
    method: 'POST',
    body: JSON.stringify({ confirmed }),
  });
  if (!res.ok) throw new Error(await parseExportError(res));
  return (await res.json()) as IndicatorExport;
}

/** 创建上传对比导出任务（需 fileToken） */
export async function createUploadComparisonExport(
  runId: string,
  fileToken: string,
  confirmed = true,
): Promise<IndicatorExport> {
  const res = await request(`/sql-runs/${encodeURIComponent(runId)}/upload-comparison-exports`, {
    method: 'POST',
    body: JSON.stringify({ confirmed, fileToken }),
  });
  if (!res.ok) {
    throw new Error(await parseExportError(res));
  }
  return (await res.json()) as IndicatorExport;
}

/** 创建诊断报告导出任务 */
export async function createDiagnosisReportExport(
  reportId: string,
  confirmed = true,
): Promise<IndicatorExport> {
  const res = await request(`/diagnosis-reports/${encodeURIComponent(reportId)}/exports`, {
    method: 'POST',
    body: JSON.stringify({ confirmed }),
  });
  if (!res.ok) {
    throw new Error(await parseExportError(res));
  }
  return (await res.json()) as IndicatorExport;
}

/** 下载导出文件（blob → 浏览器触发下载） */
export async function downloadIndicatorExport(value: IndicatorExport): Promise<void> {
  const res = await request(`/indicator-exports/${encodeURIComponent(value.exportId)}/download`);
  if (!res.ok) {
    throw new Error(await parseExportError(res));
  }
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  try {
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = value.fileName || `export-${value.exportId}.xlsx`;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
  } finally {
    URL.revokeObjectURL(url);
  }
}

/** 从错误响应体提取可读信息（对齐 services/diagnosis.ts 的 parseApiError） */
async function parseExportError(res: Response): Promise<string> {
  try {
    const body = (await res.json()) as {
      detail?: string | { message?: string };
      message?: string;
    };
    if (body.detail) {
      if (typeof body.detail === 'string') return body.detail;
      if (typeof body.detail === 'object' && body.detail.message) return body.detail.message;
    }
    if (body.message) return body.message;
  } catch {
    /* 响应体非 JSON，忽略 */
  }
  return `导出失败 (${res.status})`;
}
