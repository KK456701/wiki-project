/**
 * Web Worker 入口
 * @description 接收主线程消息，执行 IndexedDB 操作后返回结果
 */
import {
  setWorkerConfig,
  writeLogs,
  queryLogs,
  exportLogs,
  clearLogs,
  getLogCount,
} from './worker-db';
import type { WorkerMessage, WorkerResponse } from '../types';

/**
 * 安全包裹 Worker 消息处理 — 任何异常不会导致 Worker 崩溃
 */
function safeHandleMessage(event: MessageEvent<WorkerMessage>): void {
  const msg = event.data;
  try {
    handleMessage(event);
  } catch (error) {
    const errMsg = error instanceof Error ? error.message : String(error);
    postResponse({ type: 'INTERNAL_ERROR', context: msg?.type ?? 'unknown', error: errMsg });
  }
}

function handleMessage(event: MessageEvent<WorkerMessage>): void {
  const msg = event.data;

  switch (msg.type) {
    case 'WRITE_LOGS':
      writeLogs(msg.payload).then((success) => {
        postResponse({ type: 'WRITE_RESULT', requestId: msg.requestId, success });
      });
      break;

    case 'QUERY_LOGS':
      queryLogs(msg.payload).then((data) => {
        postResponse({ type: 'QUERY_RESULT', requestId: msg.requestId, data });
      });
      break;

    case 'EXPORT_LOGS':
      exportLogs().then((data) => {
        postResponse({ type: 'EXPORT_RESULT', requestId: msg.requestId, data });
      });
      break;

    case 'CLEAR_LOGS':
      clearLogs().then((success) => {
        postResponse({ type: 'CLEAR_RESULT', success });
      });
      break;

    case 'GET_COUNT':
      getLogCount().then((count) => {
        postResponse({ type: 'COUNT_RESULT', requestId: msg.requestId, count });
      });
      break;

    case 'PING':
      postResponse({ type: 'PONG' });
      break;

    default:
      break;
  }
}

/** 发送响应到主线程 */
function postResponse(response: WorkerResponse): void {
  try {
    self.postMessage(response);
  } catch {
    // postMessage 序列化失败时静默忽略
  }
}

/** 初始化 Worker — 主线程在 Bridge 初始化时注入配置 */
self.onmessage = (event: MessageEvent<WorkerMessage>) => {
  // 首次消息为配置注入
  if (event.data.type === 'SET_CONFIG') {
    try {
      setWorkerConfig(event.data.payload);
    } catch {
      // 配置注入失败，Worker 将无法工作
    }
    return;
  }

  safeHandleMessage(event);
};
