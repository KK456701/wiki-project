/**
 * 主线程 → Web Worker 通信桥
 * @description 管理 Worker 生命周期、消息序列化、请求/响应匹配
 */
/* eslint no-console: "off" */
import type {
  MonitorConfig,
  ErrorLogInput,
  ErrorLog,
  QueryFilter,
  WorkerMessage,
  WorkerResponse,
} from '../types';
import { safeExecutor } from '../core/safe-executor';

/** 待处理的请求回调 */
const pendingRequests = new Map<string, (response: WorkerResponse) => void>();

/** Worker 实例 */
let worker: Worker | null = null;

/** 是否降级到主线程模式 */
let fallbackMode = false;

/** 降级模式下的主线程 IndexedDB 模块引用（延迟加载） */
let fallbackDb: typeof import('./worker-db') | null = null;

/** 请求 ID 计数器 */
let requestIdCounter = 0;

function nextRequestId(): string {
  return `req_${++requestIdCounter}_${Date.now()}`;
}

/**
 * 初始化 Worker 连接
 */
export async function initBridge(config: MonitorConfig): Promise<void> {
  // 如果已有 Worker 在运行，先终止
  if (worker) {
    worker.terminate();
    worker = null;
  }

  try {
    // 使用 Vite Worker 语法创建 Worker
    worker = new Worker(new URL('./worker.ts', import.meta.url), { type: 'module' });

    // 监听 Worker 响应
    worker.onmessage = (event: MessageEvent<WorkerResponse>) => {
      const response = event.data;

      if (response.type === 'INTERNAL_ERROR') {
        // Worker 内部错误 — 静默记录，不影响主流程

        if (config.debug)
          console.debug('[MonitorSDK] Worker 内部异常:', response.context, response.error);
        return;
      }

      if (response.type === 'PONG') {
        // 心跳响应，处理 pending 中的 PING 请求
        return;
      }

      // 匹配请求回调
      if ('requestId' in response && response.requestId) {
        const resolve = pendingRequests.get(response.requestId);
        if (resolve) {
          pendingRequests.delete(response.requestId);
          resolve(response);
        }
      }
    };

    worker.onerror = (error) => {
      // Worker 崩溃 — 静默切换到降级模式
      if (config.debug) console.debug('[MonitorSDK] Worker 异常，降级到主线程模式:', error);
      worker?.terminate();
      worker = null;
      fallbackMode = true;
      pendingRequests.clear();
    };

    // 注入配置到 Worker
    worker.postMessage({
      type: 'SET_CONFIG',
      payload: {
        dbName: config.dbName,
        maxLogCount: config.maxLogCount,
        expireDays: config.expireDays,
      },
    });

    // 发送心跳验证 Worker 可用
    await ping();
    fallbackMode = false;
  } catch {
    // Worker 创建失败 — 降级模式，需要将配置注入到 worker-db 模块
    if (config.debug) console.debug('[MonitorSDK] Web Worker 不可用，降级到主线程模式');
    fallbackMode = true;

    // 加载降级模块并注入配置
    const mod = await getFallbackDb();
    mod.setWorkerConfig({
      dbName: config.dbName,
      maxLogCount: config.maxLogCount,
      expireDays: config.expireDays,
    });
  }
}

/** 心跳检测 */
function ping(): Promise<void> {
  return new Promise((resolve, reject) => {
    if (!worker) return reject(new Error('Worker not available'));
    const timeout = setTimeout(() => reject(new Error('Ping timeout')), 2000);
    const handler = () => {
      clearTimeout(timeout);
      worker!.removeEventListener('message', handler);
      resolve();
    };
    worker.addEventListener('message', handler);
    worker.postMessage({ type: 'PING' } as WorkerMessage);
  });
}

/** 发送消息到 Worker 并等待响应 */
async function sendToWorker<T extends WorkerResponse>(
  message: WorkerMessage & { requestId: string },
): Promise<T> {
  if (fallbackMode || !worker) {
    return (await safeExecutor.runAsync(async () => {
      const mod = await getFallbackDb();
      return handleFallback(message, mod) as Promise<T>;
    }, 'bridge-fallback')) as T;
  }

  return (await safeExecutor.runAsync(
    () =>
      new Promise<T>((resolve) => {
        pendingRequests.set(message.requestId, resolve as (response: WorkerResponse) => void);
        worker!.postMessage(message);
      }),
    'bridge-send',
  )) as T;
}

/** 延迟加载降级模式下的 DB 模块 */
async function getFallbackDb(): Promise<typeof import('./worker-db')> {
  if (!fallbackDb) {
    fallbackDb = await import('./worker-db');
  }
  return fallbackDb;
}

/** 降级模式下直接调用 DB 操作 */
async function handleFallback(
  message: WorkerMessage,
  mod: typeof import('./worker-db'),
): Promise<WorkerResponse> {
  switch (message.type) {
    case 'WRITE_LOGS': {
      const success = await mod.writeLogs(message.payload);
      return { type: 'WRITE_RESULT', requestId: message.requestId, success };
    }
    case 'QUERY_LOGS': {
      const data = await mod.queryLogs(message.payload);
      return { type: 'QUERY_RESULT', requestId: message.requestId, data };
    }
    case 'EXPORT_LOGS': {
      const exportData = await mod.exportLogs();
      return { type: 'EXPORT_RESULT', requestId: message.requestId, data: exportData };
    }
    case 'CLEAR_LOGS': {
      const clearSuccess = await mod.clearLogs();
      return { type: 'CLEAR_RESULT', success: clearSuccess };
    }
    case 'GET_COUNT': {
      const count = await mod.getLogCount();
      return { type: 'COUNT_RESULT', requestId: message.requestId, count };
    }
    default:
      return { type: 'PONG' };
  }
}

// ===== 对外暴露的 API =====

export const bridge = {
  /** 初始化 Bridge */
  init: initBridge,

  /** 批量写入日志 */
  async writeLogs(logs: ErrorLogInput[]): Promise<boolean> {
    const requestId = nextRequestId();
    const response = await sendToWorker<{
      type: 'WRITE_RESULT';
      success: boolean;
      requestId: string;
    }>({
      type: 'WRITE_LOGS',
      payload: logs,
      requestId,
    });
    return response?.success ?? false;
  },

  /** 查询日志 */
  async queryLogs(filter: QueryFilter): Promise<ErrorLog[]> {
    const requestId = nextRequestId();
    const response = await sendToWorker<{
      type: 'QUERY_RESULT';
      requestId: string;
      data: ErrorLog[];
    }>({
      type: 'QUERY_LOGS',
      payload: filter,
      requestId,
    });
    return response?.data ?? [];
  },

  /** 导出日志 */
  async exportLogs(): Promise<ErrorLog[]> {
    const requestId = nextRequestId();
    const response = await sendToWorker<{
      type: 'EXPORT_RESULT';
      requestId: string;
      data: ErrorLog[];
    }>({
      type: 'EXPORT_LOGS',
      requestId,
    });
    return response?.data ?? [];
  },

  /** 清空日志 */
  async clearLogs(): Promise<boolean> {
    const requestId = nextRequestId();
    const response = await sendToWorker<{ type: 'CLEAR_RESULT'; success: boolean }>({
      type: 'CLEAR_LOGS',
      requestId,
    });
    return response?.success ?? false;
  },

  /** 获取日志数量 */
  async getLogCount(): Promise<number> {
    const requestId = nextRequestId();
    const response = await sendToWorker<{ type: 'COUNT_RESULT'; requestId: string; count: number }>(
      {
        type: 'GET_COUNT',
        requestId,
      },
    );
    return response?.count ?? 0;
  },

  /** 终止 Worker */
  terminate(): void {
    if (worker) {
      worker.terminate();
      worker = null;
    }
    fallbackMode = false;
    fallbackDb = null;
    pendingRequests.clear();
  },
};
