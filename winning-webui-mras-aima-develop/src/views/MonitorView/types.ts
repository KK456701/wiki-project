/** MonitorView 类型定义 */
import type { ErrorType } from '@/monitor/constants';

export type { ErrorType } from '@/monitor/constants';
export type {
  ErrorLog,
  QueryFilter,
  RequestInfo,
  ResourceInfo,
  PageSnapshot,
} from '@/monitor/types';

/** 错误类型统计项 */
export interface ErrorTypeStat {
  type: ErrorType;
  count: number;
}

/** 日志详情弹窗页签 */
export type LogDetailTab = 'detail' | 'stack' | 'json';

/** 导出格式 */
export type ExportFormat = 'json' | 'csv';

/** 用户反馈提示 */
export interface FeedbackMessage {
  text: string;
  /** 对应 Vuetify 主题色 token */
  color: 'success' | 'error' | 'info';
  icon: string;
}

/** 详情弹窗中的键值对条目 */
export interface DetailField {
  label: string;
  value: string;
  /** 长文本换行展示（URL、UserAgent 等） */
  breakAll?: boolean;
}

/** 详情弹窗中的一组字段 */
export interface DetailSection {
  title: string;
  icon: string;
  fields: DetailField[];
}
