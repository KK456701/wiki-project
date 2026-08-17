/**
 * MonitorSDK — 全局前端监控 SDK 入口
 *
 * @description 提供前端运行时错误监控能力：JS 错误、Promise 拒绝、
 * 资源加载失败、HTTP 请求异常，日志持久化到 IndexedDB（通过 Web Worker）。
 * 具备无侵入、易插拔、绝对容错、性能优先四大特性。
 *
 * @example
 * ```typescript
 * // main.ts 中一行初始化
 * import { monitorSDK } from '@/monitor';
 * monitorSDK.init({ userId: () => localStorage.getItem('user_id') ?? undefined });
 * ```
 */
/* eslint no-console: "off" */
import type { MonitorConfig, ErrorLogInput, ErrorLog, QueryFilter, MonitorSDK } from './types';
import { mergeConfig, getConfig } from './config';
import { CONSOLE_OUTPUT_KEY } from './constants';
import { safeExecutor, setConfigRef } from './core/safe-executor';
import { stateManager } from './core/state';
import { bindAllListeners, unbindAllListeners, setReportCallback } from './core/listeners';
import { bridge } from './db/bridge';
import { maskErrorLog } from './mask/engine';

/** 内部就绪解析器 */
let readyResolver: (() => void) | null = null;

/** SDK 就绪 Promise（等待 Worker 初始化完成） */
const readyPromise = new Promise<void>((resolve) => {
  readyResolver = resolve;
});

// ===== 实现 =====

/** 批量刷新定时器是否已安排 */
let flushPending = false;

/** 将队列中的日志批量写入 IndexedDB */
async function flushLogs(): Promise<void> {
  flushPending = false;

  const batch = stateManager.dequeueAll();
  if (batch.length === 0) return;

  await safeExecutor.runAsync(async () => {
    await bridge.writeLogs(batch);
  }, 'flush-logs');

  stateManager.setFlushTimer(null);
}

/** 安排批量刷新 */
function scheduleFlush(): void {
  if (flushPending) return;

  const config = getConfig();

  // 队列达到阈值立即刷新
  if (stateManager.getQueueLength() >= config.flushMaxCount) {
    flushLogs();
    return;
  }

  flushPending = true;
  const timer = setTimeout(flushLogs, config.flushInterval);
  stateManager.setFlushTimer(timer);
}

/** 上报日志到批量队列 */
function reportHandler(log: ErrorLogInput): void {
  if (!stateManager.isEnabled()) return;

  const cfg = getConfig();

  // 写入前脱敏：确保落盘数据不再包含敏感信息
  // 由 safeExecutor 包裹，脱敏过程任何异常都不会影响正常日志写入
  if (cfg.mask?.enabled) {
    safeExecutor.run(() => {
      maskErrorLog(log, cfg.mask);
    }, 'mask-log');
  }

  if (cfg.debug || sessionStorage.getItem(CONSOLE_OUTPUT_KEY) === 'true') {
    console.error(`[MonitorSDK] ${log.type}: ${log.message}`, log.stack ?? '', log);
  }

  safeExecutor.run(() => {
    stateManager.enqueue(log);
    scheduleFlush();
  }, 'report-handler');
}

// ===== 公开 API =====

export const monitorSDK: MonitorSDK = {
  init(config?: Partial<MonitorConfig>): void {
    // 防止重复初始化
    if (stateManager.isEnabled()) return;

    // 合并配置
    if (config) {
      mergeConfig(config);
    }
    const cfg = getConfig();

    // 注入配置引用到 safeExecutor（用于 debug 输出）
    setConfigRef(cfg);

    // 注入 report 回调到监听器（避免循环依赖）
    setReportCallback(reportHandler);

    // 初始化 Worker Bridge
    bridge.init(cfg).then(() => {
      readyResolver?.();
    });

    // 如果配置为 enabled，立即启用
    if (cfg.enabled) {
      this.enable();
    }
  },

  enable(): void {
    if (stateManager.isEnabled()) return;

    safeExecutor.run(() => {
      // ⚠️ 顺序关键：必须先绑定监听器，再标记为已启用。
      // bindAllListeners() 内部检查 isEnabled() 防止重复绑定，
      // 如果先 setEnabled(true) 再 bindAllListeners() 会导致监听器永远不会被绑定。
      bindAllListeners();
      stateManager.setEnabled(true);

      if (getConfig().debug) console.debug('[MonitorSDK] 监控已启用');
    }, 'enable');
  },

  disable(clearLogs = false): void {
    if (!stateManager.isEnabled()) return;

    safeExecutor.run(() => {
      // 先刷新队列中残留的日志
      flushLogs();

      // 移除所有监听器
      unbindAllListeners();

      // 终止 Worker
      bridge.terminate();

      // 清理状态
      stateManager.reset();
      flushPending = false;

      // 可选：清除已存储的日志
      if (clearLogs) {
        bridge.clearLogs();
      }

      if (getConfig().debug) console.debug('[MonitorSDK] 监控已禁用');
    }, 'disable');
  },

  report(error: ErrorLogInput): void {
    reportHandler(error);
  },

  async queryLogs(filter: QueryFilter): Promise<ErrorLog[]> {
    await readyPromise;
    return await safeExecutor.safePromise(bridge.queryLogs(filter), [], 'query-logs');
  },

  async exportLogs(): Promise<ErrorLog[]> {
    await readyPromise;
    return await safeExecutor.safePromise(bridge.exportLogs(), [], 'export-logs');
  },

  async clearLogs(): Promise<void> {
    await readyPromise;
    await safeExecutor.runAsync(async () => {
      await bridge.clearLogs();
    }, 'clear-logs');
  },

  isEnabled(): boolean {
    return stateManager.isEnabled();
  },

  async getLogCount(): Promise<number> {
    await readyPromise;
    return await safeExecutor.safePromise(bridge.getLogCount(), 0, 'get-count');
  },
};
