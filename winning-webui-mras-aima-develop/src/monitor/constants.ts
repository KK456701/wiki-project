/**
 * MonitorSDK 常量定义
 * @description 错误类型枚举、默认配置等常量
 */
import type { MonitorConfig } from './types';

/** 错误类型枚举 */
export const ERROR_TYPE = {
  JS_ERROR: 'js_error',
  PROMISE_REJECTION: 'promise_rejection',
  RESOURCE_ERROR: 'resource_error',
  HTTP_ERROR: 'http_error',
  VUE_ERROR: 'vue_error',
} as const;

/** sessionStorage 键 — 生产环境下在控制台写入 'true' 即可开启控制台错误输出 */
export const CONSOLE_OUTPUT_KEY = '__mras_aima_monitor_console__';

/** 所有错误类型列表 */
export const ALL_ERROR_TYPES = Object.values(ERROR_TYPE) as ErrorType[];

/** ErrorType 联合类型 */
export type ErrorType = (typeof ERROR_TYPE)[keyof typeof ERROR_TYPE];

/** 默认配置 */
export const DEFAULT_CONFIG: MonitorConfig = {
  enabled: true,
  userId: () => undefined,
  capturePageSnapshot: false,
  dbName: '__mras_aima_monitor_db__',
  maxLogCount: 1000,
  // TODO: 当前暂未实现基于 maxLogSize 的日志回收
  maxLogSize: 50 * 1024 * 1024,
  expireDays: 7,
  flushInterval: 1000,
  flushMaxCount: 50,
  debug: false,
  captureTypes: [...ALL_ERROR_TYPES],
  fetchUrlFilter: () => true,
  mask: {
    enabled: true,
    fields: [],
    globalRules: ['phone', 'idCard', 'email', 'token', 'urlSecretParam', 'ipv4', 'bankCard'],
  },
};
