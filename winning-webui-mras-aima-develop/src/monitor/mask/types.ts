/**
 * MonitorSDK 数据脱敏 — 类型定义
 *
 * @description 定义脱敏策略枚举、单条脱敏规则结构、字段级配置以及
 * 挂载在 MonitorConfig 下的完整脱敏配置接口。
 */
/** 脱敏策略 */
export const MASK_STRATEGY = {
  /** 替换为固定字符（如 ***) */
  REPLACE: 'replace',
  /** 保留首尾字符，中间替换为 *** */
  KEEP_ENDS: 'keep-ends',
  /** 完全移除匹配内容 */
  REMOVE: 'remove',
  /** 自定义替换函数 */
  CUSTOM: 'custom',
} as const;

export type MaskStrategy = (typeof MASK_STRATEGY)[keyof typeof MASK_STRATEGY];

/**
 * 单条脱敏规则
 *
 * @example
 * ```typescript
 * const phoneRule: MaskRule = {
 *   name: 'phone',
 *   pattern: /1[3-9]\d{9}/g,
 *   strategy: MASK_STRATEGY.KEEP_ENDS,
 *   replaceValue: { head: 3, tail: 4 },
 * };
 * ```
 */
export interface MaskRule {
  /** 规则名称（用于配置引用和调试日志） */
  name: string;
  /** 匹配模式：必须带 g 标志的正则表达式 */
  pattern: RegExp;
  /** 脱敏策略 */
  strategy: MaskStrategy;
  /**
   * 策略参数：
   * - REPLACE: 替换字符串（默认 '***'）
   * - KEEP_ENDS: 保留首尾字符数 `{ head, tail }`
   * - REMOVE: 无需参数
   * - CUSTOM: 自定义函数 `(match, ...captures) => maskedString`
   */
  replaceValue?:
    string | { head: number; tail: number } | ((match: string, ...captures: string[]) => string);
  /** 规则是否启用（默认 true） */
  enabled?: boolean;
}

/**
 * 可脱敏的 ErrorLog 字段路径
 *
 * 支持嵌套路径（如 'requestInfo.url'），在引擎中通过点号分割递归取值。
 */
export type ErrorLogMaskableField =
  | 'message'
  | 'url'
  | 'userId'
  | 'stack'
  | 'requestInfo.url'
  | 'requestInfo.statusText'
  | 'resourceInfo.src'
  | 'resourceInfo.outerHTML';

/** 字段级脱敏配置 */
export interface FieldMaskConfig {
  /** 要脱敏的字段路径（单个或多个） */
  field: ErrorLogMaskableField | ErrorLogMaskableField[];
  /** 应用的规则名称列表（对应内置规则或自定义规则的 name） */
  rules: string[];
}

/**
 * 完整的脱敏配置（挂载在 MonitorConfig 下）
 *
 * @example
 * ```typescript
 * monitorSDK.init({
 *   mask: {
 *     enabled: true,
 *     fields: [
 *       { field: ['message', 'url'], rules: ['phone', 'idCard'] },
 *     ],
 *     globalRules: [],
 *   },
 * });
 * ```
 */
export interface MaskConfig {
  /** 是否启用脱敏（默认 false，需显式开启） */
  enabled: boolean;
  /** 字段级脱敏规则配置 */
  fields: FieldMaskConfig[];
  /** 全局规则：对所有可脱敏字段（message/url/userId 等）生效 */
  globalRules: string[];
  /** 用户自定义的额外规则（会合并到内置规则中，同名覆盖） */
  customRules?: Record<string, MaskRule>;
}
