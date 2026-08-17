/**
 * 用户标识工具
 * @description 从配置的 userId 获取函数中提取用户标识
 */
import type { MonitorConfig } from '../types';

/** 获取当前用户标识 */
export function getUserIdentity(config: MonitorConfig): string | undefined {
  try {
    return config.userId();
  } catch {
    return undefined;
  }
}
