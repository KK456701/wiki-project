/**
 * 全局监听器模块
 * @description 绑定/解绑所有全局错误监听器（window.onerror、unhandledrejection、
 * 资源加载错误、fetch 劫持）。所有回调均经 safeExecutor 安全包裹。
 */
import type { ErrorLogInput } from '../types';
import { ERROR_TYPE } from '../constants';
import { safeExecutor } from './safe-executor';
import { stateManager } from './state';
import { getConfig } from '../config';
import { capturePageSnapshot } from '../utils/page-snapshot';
import { getUserIdentity } from '../utils/user-identity';

/**
 * report 回调 — 由 SDK 入口注入，避免循环依赖
 */
let reportCallback: ((log: ErrorLogInput) => void) | null = null;

export function setReportCallback(cb: (log: ErrorLogInput) => void): void {
  reportCallback = cb;
}

/** 安全地将错误信息上报到 SDK */
function safeReport(logInput: Omit<ErrorLogInput, 'userId' | 'pageSnapshot'>): void {
  if (!stateManager.isEnabled()) return;
  safeExecutor.run(() => {
    const config = getConfig();
    // 类型过滤
    if (!config.captureTypes.includes(logInput.type)) return;

    const log: ErrorLogInput = {
      ...logInput,
      userId: safeExecutor.run(() => getUserIdentity(config)) ?? undefined,
      pageSnapshot: config.capturePageSnapshot
        ? (safeExecutor.run(() => capturePageSnapshot()) ?? undefined)
        : undefined,
    };
    reportCallback?.(log);
  }, `listener-${logInput.type}`);
}

// ===== 事件处理器（具名函数，用于精确移除） =====

/** JS 运行时错误 */
function onJsErrorHandler(event: ErrorEvent): void {
  // 过滤：只处理 JS 错误，资源加载错误单独处理
  if (!(event instanceof ErrorEvent) || event.target !== window) return;

  safeReport({
    type: ERROR_TYPE.JS_ERROR,
    message: event.message,
    stack: safeExecutor.run(() => event.error?.stack, 'extract-stack') ?? '',
    timestamp: Date.now(),
    url: safeExecutor.run(() => location.href, 'get-href') ?? '',
    extra: {
      filename: event.filename,
      lineno: event.lineno,
      colno: event.colno,
    },
  });
}

/** 未处理的 Promise 拒绝 */
function onUnhandledRejectionHandler(event: PromiseRejectionEvent): void {
  const reason = event.reason;

  safeReport({
    type: ERROR_TYPE.PROMISE_REJECTION,
    message: reason instanceof Error ? reason.message : String(reason),
    stack: reason instanceof Error ? (reason.stack ?? '') : '',
    timestamp: Date.now(),
    url: safeExecutor.run(() => location.href, 'get-href') ?? '',
  });
}

/** 资源加载失败（捕获阶段） */
function onResourceErrorHandler(event: Event): void {
  const target = event.target as HTMLElement | null;
  if (!target || !('src' in target || 'href' in target)) return;

  const tagName = target.tagName.toLowerCase();
  const src =
    (target as HTMLScriptElement).src ||
    (target as HTMLLinkElement).href ||
    (target as HTMLImageElement).src;

  safeReport({
    type: ERROR_TYPE.RESOURCE_ERROR,
    message: `资源加载失败: ${tagName} — ${src}`,
    timestamp: Date.now(),
    url: safeExecutor.run(() => location.href, 'get-href') ?? '',
    resourceInfo: {
      tagName,
      src,
      outerHTML: target.outerHTML?.slice(0, 200) ?? '',
    },
  });
}

// ===== fetch 劫持 =====

let originalFetch: typeof window.fetch | null = null;
let fetchHijacked = false;

/**
 * 劫持后的 fetch 实现 — 记录失败请求但不改变 fetch 行为
 */
async function hijackedFetch(...args: Parameters<typeof fetch>): Promise<Response> {
  const startTime = Date.now();

  try {
    const response = await originalFetch!(...args);

    // 仅记录 HTTP 4xx/5xx 错误响应
    if (!response.ok) {
      safeExecutor.run(() => {
        const [url, options] = args;
        const config = getConfig();
        const requestUrl = typeof url === 'string' ? url : url.toString();
        if (!config.fetchUrlFilter(requestUrl)) return;

        safeReport({
          type: ERROR_TYPE.HTTP_ERROR,
          message: `HTTP ${response.status}: ${response.statusText}`,
          timestamp: Date.now(),
          url: location.href,
          requestInfo: {
            method: options?.method ?? 'GET',
            url: requestUrl,
            status: response.status,
            statusText: response.statusText ?? '',
            duration: Date.now() - startTime,
          },
        });
      }, 'fetch-http-error');
    }

    return response;
  } catch (error) {
    // 网络错误（fetch 本身抛出）
    safeExecutor.run(() => {
      const [url, options] = args;
      const config = getConfig();
      const requestUrl = typeof url === 'string' ? url : url.toString();
      if (!config.fetchUrlFilter(requestUrl)) return;

      safeReport({
        type: ERROR_TYPE.HTTP_ERROR,
        message: `网络请求失败: ${error instanceof Error ? error.message : String(error)}`,
        timestamp: Date.now(),
        url: location.href,
        requestInfo: {
          method: options?.method ?? 'GET',
          url: requestUrl,
          duration: Date.now() - startTime,
        },
      });
    }, 'fetch-network-error');

    throw error; // 仍然抛出，不改变 fetch 原有行为
  }
}

// ===== 监听器管理 =====

/** 事件监听器注册条目 */
interface ListenerEntry {
  type: string;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  handler: (event: any) => void;
  options?:
    boolean | { capture?: boolean; once?: boolean; passive?: boolean; signal?: AbortSignal };
}

/** 已绑定的事件监听器注册表 */
const boundListeners = new Map<string, ListenerEntry>();

/**
 * 绑定所有全局监听器
 */
export function bindAllListeners(): void {
  if (stateManager.isEnabled()) return; // 防止重复绑定

  // 1. JS 运行时错误
  window.addEventListener('error', onJsErrorHandler);
  boundListeners.set('js-error', { type: 'error', handler: onJsErrorHandler });

  // 2. 未处理的 Promise 拒绝
  window.addEventListener('unhandledrejection', onUnhandledRejectionHandler);
  boundListeners.set('unhandledrejection', {
    type: 'unhandledrejection',
    handler: onUnhandledRejectionHandler,
  });

  // 3. 资源加载错误（捕获阶段）
  window.addEventListener('error', onResourceErrorHandler, true);
  boundListeners.set('resource-error', {
    type: 'error',
    handler: onResourceErrorHandler,
    options: { capture: true },
  });

  // 4. 劫持 fetch
  if (!fetchHijacked) {
    originalFetch = window.fetch;
    window.fetch = hijackedFetch;
    fetchHijacked = true;
  }
}

/**
 * 解绑所有全局监听器
 */
export function unbindAllListeners(): void {
  // 移除事件监听
  for (const [, entry] of boundListeners) {
    window.removeEventListener(entry.type, entry.handler, entry.options);
  }
  boundListeners.clear();

  // 恢复 fetch
  if (fetchHijacked && originalFetch) {
    window.fetch = originalFetch;
    originalFetch = null;
    fetchHijacked = false;
  }
}
