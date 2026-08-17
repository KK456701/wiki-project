/**
 * MonitorSDK 类型定义
 */
import type { ErrorType } from './constants';
import type { MaskConfig } from './mask/types';

/** 监控配置 */
export interface MonitorConfig {
  /** 是否启用（默认 true） */
  enabled: boolean;
  /** 用户标识获取函数 */
  userId: () => string | undefined;
  /** 是否捕获页面状态快照（默认 false） */
  capturePageSnapshot: boolean;
  /** 数据库名称 */
  dbName: string;
  /** 日志最大条数 */
  maxLogCount: number;
  /** 日志最大总大小（字节） */
  maxLogSize: number;
  /** 日志过期天数 */
  expireDays: number;
  /** 批量写入 debounce 间隔（毫秒） */
  flushInterval: number;
  /** 批量写入最大条数 */
  flushMaxCount: number;
  /** 调试模式 */
  debug: boolean;
  /** 错误类型白名单 */
  captureTypes: ErrorType[];
  /** fetch 监控 URL 过滤器 */
  fetchUrlFilter: (url: string) => boolean;
  /** 数据脱敏配置 */
  mask: MaskConfig;
}

/** 页面状态快照 */
export interface PageSnapshot {
  /** 当前路由路径 */
  route: string;
  /** 页面标题 */
  title: string;
  /** UserAgent */
  userAgent: string;
  /** 屏幕分辨率 */
  screenResolution: string;
}

/** HTTP 请求错误附加信息 */
export interface RequestInfo {
  method: string;
  url: string;
  status?: number;
  statusText?: string;
  duration: number;
}

/** 资源加载错误附加信息 */
export interface ResourceInfo {
  tagName: string;
  src: string;
  outerHTML: string;
}

/** 日志输入（上报时使用） */
export interface ErrorLogInput {
  type: ErrorType;
  message: string;
  stack?: string;
  timestamp: number;
  url: string;
  userId?: string;
  pageSnapshot?: PageSnapshot;
  requestInfo?: RequestInfo;
  resourceInfo?: ResourceInfo;
  extra?: Record<string, unknown>;
}

/** 日志实体（含主键，从 DB 读取时使用） */
export interface ErrorLog extends ErrorLogInput {
  id: number;
}

/** 日志查询过滤条件 */
export interface QueryFilter {
  /** 错误类型过滤 */
  types?: ErrorType[];
  /** 起始时间 (Unix ms) */
  startTime?: number;
  /** 结束时间 (Unix ms) */
  endTime?: number;
  /** 用户标识过滤 */
  userId?: string;
  /** 分页：偏移量 */
  offset?: number;
  /** 分页：条数 */
  limit?: number;
}

/** Worker 通信消息类型 */
export type WorkerMessage =
  | { type: 'SET_CONFIG'; payload: { dbName: string; maxLogCount: number; expireDays: number } }
  | { type: 'WRITE_LOGS'; payload: ErrorLogInput[]; requestId: string }
  | { type: 'QUERY_LOGS'; payload: QueryFilter; requestId: string }
  | { type: 'EXPORT_LOGS'; requestId: string }
  | { type: 'CLEAR_LOGS' }
  | { type: 'GET_COUNT'; requestId: string }
  | { type: 'PING' };

/** Worker 响应消息类型 */
export type WorkerResponse =
  | { type: 'WRITE_RESULT'; success: boolean; error?: string; requestId: string }
  | { type: 'QUERY_RESULT'; requestId: string; data: ErrorLog[] }
  | { type: 'EXPORT_RESULT'; requestId: string; data: ErrorLog[] }
  | { type: 'CLEAR_RESULT'; success: boolean }
  | { type: 'COUNT_RESULT'; requestId: string; count: number }
  | { type: 'PONG' }
  | { type: 'INTERNAL_ERROR'; context: string; error: string };

/** MonitorSDK 公开 API */
export interface MonitorSDK {
  init(config?: Partial<MonitorConfig>): void;
  enable(): void;
  disable(clearLogs?: boolean): void;
  report(error: ErrorLogInput): void;
  queryLogs(filter: QueryFilter): Promise<ErrorLog[]>;
  exportLogs(): Promise<ErrorLog[]>;
  clearLogs(): Promise<void>;
  isEnabled(): boolean;
  getLogCount(): Promise<number>;
}
