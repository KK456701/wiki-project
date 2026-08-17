import type { DataTableHeader } from 'vuetify';
import type { ErrorType } from '@/monitor/constants';
import { ERROR_TYPE } from '@/monitor/constants';
import type { ExportFormat } from './types';

/** ErrorType → 中文标签 */
export const ERROR_TYPE_LABEL: Record<ErrorType, string> = {
  [ERROR_TYPE.JS_ERROR]: 'JS 错误',
  [ERROR_TYPE.PROMISE_REJECTION]: 'Promise 拒绝',
  [ERROR_TYPE.RESOURCE_ERROR]: '资源加载错误',
  [ERROR_TYPE.HTTP_ERROR]: 'HTTP 请求错误',
  [ERROR_TYPE.VUE_ERROR]: 'Vue 组件错误',
} as const;

/** ErrorType → 颜色（对应 Vuetify 主题色 token） */
export const ERROR_TYPE_COLOR: Record<ErrorType, string> = {
  [ERROR_TYPE.JS_ERROR]: 'error',
  [ERROR_TYPE.PROMISE_REJECTION]: 'warning',
  [ERROR_TYPE.RESOURCE_ERROR]: 'orange',
  [ERROR_TYPE.HTTP_ERROR]: 'info',
  [ERROR_TYPE.VUE_ERROR]: 'purple',
} as const;

/** ErrorType → 图标 */
export const ERROR_TYPE_ICON: Record<ErrorType, string> = {
  [ERROR_TYPE.JS_ERROR]: 'mdi-language-javascript',
  [ERROR_TYPE.PROMISE_REJECTION]: 'mdi-alert-octagon',
  [ERROR_TYPE.RESOURCE_ERROR]: 'mdi-file-alert',
  [ERROR_TYPE.HTTP_ERROR]: 'mdi-api',
  [ERROR_TYPE.VUE_ERROR]: 'mdi-vuejs',
} as const;

/** 状态标签常量 */
export const SDK_STATUS_LABEL = {
  ENABLED: '监控中',
  DISABLED: '已暂停',
} as const;

/** 导出格式选项 */
export const EXPORT_FORMAT_OPTIONS: { label: string; value: ExportFormat; icon: string }[] = [
  { label: 'JSON', value: 'json', icon: 'mdi-code-json' },
  { label: 'CSV', value: 'csv', icon: 'mdi-file-delimited-outline' },
];

/** 每页条数选项（表格 footer 会自带「每页条数」标签，故标题只保留数字） */
export const PAGE_SIZE_OPTIONS = [
  { value: 20, title: '20' },
  { value: 50, title: '50' },
  { value: 100, title: '100' },
];

/** 默认每页条数 */
export const DEFAULT_PAGE_SIZE = 20;

/** 日志表格列定义 */
export const LOG_TABLE_HEADERS: DataTableHeader[] = [
  { title: 'ID', key: 'id', align: 'start', width: 88, nowrap: true },
  { title: '类型', key: 'type', align: 'start', width: 140, nowrap: true },
  { title: '错误信息', key: 'message', align: 'start', sortable: false, minWidth: '280px' },
  { title: '发生时间', key: 'timestamp', align: 'start', width: 180, nowrap: true },
  { title: '操作', key: 'actions', align: 'end', width: 72, sortable: false, nowrap: true },
];

/** 关键词搜索防抖间隔（毫秒） */
export const KEYWORD_DEBOUNCE_MS = 300;

/** 提示条展示时长（毫秒） */
export const SNACKBAR_TIMEOUT = 2500;

/** 表格中错误信息的最大展示宽度 */
export const MESSAGE_MAX_WIDTH = '420px';
