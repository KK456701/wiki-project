/**
 * 脱敏引擎
 *
 * @description 根据 MaskConfig 对 ErrorLogInput 对象的指定字段应用脱敏规则。
 * 所有操作均为原地修改，不创建新对象，以减少 GC 压力。
 *
 * 执行流程：
 * 1. 解析配置，合并内置规则与自定义规则
 * 2. 遍历字段级配置 → 对每个字段应用指定规则
 * 3. 遍历全局规则 → 对所有可脱敏字段逐一应用
 */
import type { ErrorLogInput } from '../types';
import type { MaskConfig, MaskRule } from './types';
import { MASK_STRATEGY } from './types';
import { BUILTIN_MASK_RULES } from './rules';

// ====== 字段访问工具 ======

/**
 * 获取嵌套字段值
 *
 * @param log - 日志对象
 * @param fieldPath - 点号分隔的路径（如 'requestInfo.url'）
 * @returns 字段值，不存在时返回 undefined
 */
function getFieldValue(log: ErrorLogInput, fieldPath: string): unknown {
  const parts = fieldPath.split('.');
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  let current: any = log;
  for (const part of parts) {
    if (current === null || current === undefined) return undefined;
    current = current[part];
  }
  return current;
}

/**
 * 设置嵌套字段值（原地修改）
 *
 * @param log - 日志对象
 * @param fieldPath - 点号分隔的路径
 * @param value - 新值
 */
function setFieldValue(log: ErrorLogInput, fieldPath: string, value: unknown): void {
  const parts = fieldPath.split('.');
  const lastPart = parts.pop();
  if (!lastPart) return;

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  let current: any = log;
  for (const part of parts) {
    if (current === null || current === undefined) return;
    current = current[part];
  }
  if (current && typeof current === 'object') {
    current[lastPart] = value;
  }
}

// ====== 规则应用 ======

/**
 * 应用单条脱敏规则到字符串
 */
function applyRule(value: string, rule: MaskRule): string {
  const { pattern, strategy, replaceValue } = rule;

  switch (strategy) {
    case MASK_STRATEGY.REPLACE: {
      const replacement = typeof replaceValue === 'string' ? replaceValue : '***';
      return value.replace(pattern, replacement);
    }

    case MASK_STRATEGY.KEEP_ENDS: {
      const opts = (replaceValue as { head: number; tail: number } | undefined) ?? {
        head: 3,
        tail: 4,
      };
      return value.replace(pattern, (match) => {
        if (match.length <= opts.head + opts.tail) {
          return '*'.repeat(match.length);
        }
        return match.slice(0, opts.head) + '***' + match.slice(-opts.tail);
      });
    }

    case MASK_STRATEGY.REMOVE:
      return value.replace(pattern, '');

    case MASK_STRATEGY.CUSTOM: {
      const fn = replaceValue as ((match: string, ...captures: string[]) => string) | undefined;
      if (typeof fn !== 'function') return value;
      return value.replace(pattern, (...args) => {
        // String.replace 回调：args = [match, ...captures, offset, fullString]
        // 仅透传 match 与捕获组，屏蔽 offset/fullString
        return fn(args[0], ...(args.slice(1, args.length - 2) as string[]));
      });
    }

    default:
      return value;
  }
}

/**
 * 对 ErrorLogInput 的指定字段应用脱敏规则（原地修改）
 */
function maskField(log: ErrorLogInput, fieldPath: string, rule: MaskRule): void {
  const value = getFieldValue(log, fieldPath);
  if (typeof value !== 'string' || value.length === 0) return;

  const masked = applyRule(value, rule);
  if (masked !== value) {
    setFieldValue(log, fieldPath, masked);
  }
}

/** 合并内置规则与自定义规则为 Map */
function resolveRules(customRules?: Record<string, MaskRule>): Map<string, MaskRule> {
  const merged = { ...BUILTIN_MASK_RULES, ...customRules };
  const enabled = new Map<string, MaskRule>();

  for (const [name, rule] of Object.entries(merged)) {
    if (rule.enabled !== false) {
      enabled.set(name, rule);
    }
  }

  return enabled;
}

/**
 * 应用所有规则到所有可脱敏字段的默认字段列表
 */
const GLOBAL_FIELDS = [
  'message',
  'url',
  'userId',
  'stack',
  'requestInfo.url',
  'requestInfo.statusText',
  'resourceInfo.src',
  'resourceInfo.outerHTML',
] as const;

// ====== 公开 API ======

/**
 * 脱敏引擎入口
 *
 * 对 ErrorLogInput 原地应用所有配置的脱敏规则。
 * 在 `reportHandler()` 中、`enqueue()` 之前调用，确保落盘数据即安全。
 *
 * @param log - 待脱敏的日志对象（原地修改）
 * @param config - 脱敏配置（来自 MonitorConfig.mask）
 */
export function maskErrorLog(log: ErrorLogInput, config: MaskConfig): void {
  if (!config.enabled) return;

  const allRules = resolveRules(config.customRules);

  // 1) 字段级规则
  for (const fieldConfig of config.fields) {
    const fieldNames = Array.isArray(fieldConfig.field) ? fieldConfig.field : [fieldConfig.field];

    for (const fieldName of fieldNames) {
      for (const ruleName of fieldConfig.rules) {
        const rule = allRules.get(ruleName);
        if (rule) {
          maskField(log, fieldName, rule);
        }
      }
    }
  }

  // 2) 全局规则
  for (const ruleName of config.globalRules) {
    const rule = allRules.get(ruleName);
    if (!rule) continue;

    for (const field of GLOBAL_FIELDS) {
      maskField(log, field, rule);
    }
  }
}
