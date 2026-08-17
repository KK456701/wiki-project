import { format } from 'date-fns';
import type { ErrorLog } from '@/monitor/types';
import type { ExportFormat } from '../types';
import { ERROR_TYPE_LABEL } from '../constants';

/** CSV 列定义 */
const CSV_COLUMNS: { header: string; pick: (log: ErrorLog) => unknown }[] = [
  { header: 'id', pick: (log) => log.id },
  { header: 'type', pick: (log) => log.type },
  { header: 'typeLabel', pick: (log) => ERROR_TYPE_LABEL[log.type] },
  { header: 'message', pick: (log) => log.message },
  { header: 'url', pick: (log) => log.url },
  { header: 'timestamp', pick: (log) => log.timestamp },
  { header: 'datetime', pick: (log) => format(log.timestamp, 'yyyy-MM-dd HH:mm:ss') },
  { header: 'userId', pick: (log) => log.userId },
  { header: 'stack', pick: (log) => log.stack },
];

/** 按 RFC 4180 转义单个 CSV 单元格 */
function escapeCsvCell(value: unknown): string {
  if (value === undefined || value === null) return '';
  const text = String(value);
  return /[",\n\r]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
}

function toCsv(logs: ErrorLog[]): string {
  const header = CSV_COLUMNS.map((col) => col.header).join(',');
  const rows = logs.map((log) => CSV_COLUMNS.map((col) => escapeCsvCell(col.pick(log))).join(','));
  return [header, ...rows].join('\r\n');
}

/**
 * 触发浏览器下载
 *
 * 两个易踩的坑：
 * 1. Firefox 要求 <a> 已挂载到文档中才会响应 click()
 * 2. 立即 revokeObjectURL 会让部分浏览器来不及读取数据，需延后释放
 */
function download(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  anchor.style.display = 'none';
  document.body.appendChild(anchor);
  anchor.click();
  document.body.removeChild(anchor);
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export function useLogExport() {
  /**
   * 导出日志文件
   * @returns 实际导出的条数
   */
  function exportLogs(logs: ErrorLog[], exportFormat: ExportFormat): number {
    const stamp = format(Date.now(), 'yyyyMMdd-HHmmss');

    if (exportFormat === 'csv') {
      // 加 UTF-8 BOM，避免 Excel 打开时中文乱码
      const blob = new Blob(['\uFEFF' + toCsv(logs)], { type: 'text/csv;charset=utf-8' });
      download(blob, `monitor-logs-${stamp}.csv`);
    } else {
      const blob = new Blob([JSON.stringify(logs, null, 2)], {
        type: 'application/json;charset=utf-8',
      });
      download(blob, `monitor-logs-${stamp}.json`);
    }

    return logs.length;
  }

  return { exportLogs };
}
