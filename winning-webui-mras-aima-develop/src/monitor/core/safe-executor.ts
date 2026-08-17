/**
 * 安全执行器 — MonitorSDK 的"防爆墙"
 *
 * 所有监听回调、IndexedDB 操作、Worker 通信都必须经过 safeExecutor 包裹。
 * 任何内部异常都不会泄漏到全局作用域，确保监控模块的异常不导致业务中断。
 */
/* eslint no-console: "off" */
import type { MonitorConfig } from '../types';

let configRef: MonitorConfig | null = null;

/**
 * 注入配置引用（避免循环依赖）
 */
export function setConfigRef(cfg: MonitorConfig): void {
  configRef = cfg;
}

export const safeExecutor = {
  /**
   * 安全执行同步函数
   * @returns 执行成功返回结果，失败返回 undefined
   */
  run<T>(fn: () => T, context?: string): T | undefined {
    try {
      return fn();
    } catch (error) {
      if (configRef?.debug)
        console.debug(`[MonitorSDK] 内部异常已捕获 [${context ?? 'sync'}]:`, error);
      return undefined;
    }
  },

  /**
   * 安全执行异步函数
   * @returns 总是 resolve 的 Promise（失败时返回 undefined）
   */
  async runAsync<T>(fn: () => Promise<T>, context?: string): Promise<T | undefined> {
    try {
      return await fn();
    } catch (error) {
      if (configRef?.debug)
        console.debug(`[MonitorSDK] 内部异常已捕获 [${context ?? 'async'}]:`, error);
      return undefined;
    }
  },

  /**
   * 安全包裹 Promise — 不会 reject，失败时静默返回默认值
   */
  async safePromise<T>(promise: Promise<T>, fallback: T, context?: string): Promise<T> {
    try {
      return await promise;
    } catch (error) {
      if (configRef?.debug)
        console.debug(`[MonitorSDK] 内部异常已捕获 [${context ?? 'promise'}]:`, error);
      return fallback;
    }
  },
};
