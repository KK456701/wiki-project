/**
 * 内部状态管理
 * @description 管理 MonitorSDK 的 enable/disable 状态
 */
import type { ErrorLogInput } from '../types';

/** 是否已启用 */
let enabled = false;

/** 批量写入队列 */
let flushQueue: ErrorLogInput[] = [];

/** 批量写入定时器 ID */
let flushTimerId: ReturnType<typeof setTimeout> | null = null;

export const stateManager = {
  isEnabled(): boolean {
    return enabled;
  },

  setEnabled(val: boolean): void {
    enabled = val;
  },

  /** 将日志加入批量队列 */
  enqueue(log: ErrorLogInput): void {
    flushQueue.push(log);
  },

  /** 取出并清空队列 */
  dequeueAll(): ErrorLogInput[] {
    const batch = flushQueue;
    flushQueue = [];
    return batch;
  },

  getQueueLength(): number {
    return flushQueue.length;
  },

  setFlushTimer(timer: ReturnType<typeof setTimeout> | null): void {
    flushTimerId = timer;
  },

  getFlushTimer(): ReturnType<typeof setTimeout> | null {
    return flushTimerId;
  },

  /** 清理所有状态 */
  reset(): void {
    enabled = false;
    flushQueue = [];
    if (flushTimerId !== null) {
      clearTimeout(flushTimerId);
      flushTimerId = null;
    }
  },
};
