/**
 * 配置管理
 * @description 合并用户配置与默认配置，暴露当前生效配置
 */
import type { MonitorConfig } from './types';
import { DEFAULT_CONFIG } from './constants';

let config: MonitorConfig = { ...DEFAULT_CONFIG };

/** 获取当前配置（只读） */
export function getConfig(): Readonly<MonitorConfig> {
  return config;
}

/** 合并配置 */
export function mergeConfig(userConfig: Partial<MonitorConfig>): void {
  config = { ...config, ...userConfig };

  // 深拷贝引用类型字段（防止外部修改影响内部）
  if (userConfig.captureTypes) {
    config.captureTypes = [...userConfig.captureTypes];
  }
  if (userConfig.userId) {
    config.userId = userConfig.userId;
  }
  if (userConfig.fetchUrlFilter) {
    config.fetchUrlFilter = userConfig.fetchUrlFilter;
  }
}

/** 重置为默认配置 */
export function resetConfig(): void {
  config = { ...DEFAULT_CONFIG };
}
