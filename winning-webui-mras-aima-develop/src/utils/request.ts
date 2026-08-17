import { API_BASE_PREFIX } from '@/config/app';
import router from '@/router';
import { getStorage, removeStorage } from '@/storage/storage';
import { STORAGE_KEYS } from '@/storage/storage-defs';

/** API 基础路径（后端统一前缀） */
export const API_BASE = `${API_BASE_PREFIX}/api`;

/**
 * 401 未授权错误
 *
 * 当 fetch 响应状态码为 401 时，统一清除认证信息并跳转登录页后抛出此错误。
 * 调用方可通过 `instanceof UnauthorizedError` 进行类型判断以做差异化处理。
 */
export class UnauthorizedError extends Error {
  constructor() {
    super('未授权，请重新登录');
    this.name = 'UnauthorizedError';
  }
}

/**
 * 清除认证信息并跳转登录页
 *
 * 调用后立即抛出 UnauthorizedError，调用方无需处理返回值。
 */
export function handleUnauthorized(): never {
  removeStorage(STORAGE_KEYS.AUTH_TOKEN);
  removeStorage(STORAGE_KEYS.USER_INFO);
  router.push('/login');
  throw new UnauthorizedError();
}

/**
 * 获取认证请求头
 *
 * 自动从 sessionStorage 读取 Token 并组装 Authorization 头。
 * 供 request() 和 SSE 流复用。
 */
export function getAuthHeaders(): Record<string, string> {
  const headers: Record<string, string> = {};
  const token = getStorage(STORAGE_KEYS.AUTH_TOKEN);
  if (token) {
    headers['Authorization'] = token;
  }
  return headers;
}

/**
 * 构建完整请求 URL
 *
 * - 相对路径（如 `/agent/capabilities`）→ 自动补全 API_BASE 前缀
 * - 绝对 URL（以 `http` 开头）→ 原样返回
 */
export function buildUrl(path: string): string {
  return path.startsWith('http') ? path : `${API_BASE}${path}`;
}

/** 请求配置（在 fetch 原生配置之上扩展超时与重试） */
export interface RequestOptions extends RequestInit {
  /** 超时时间（毫秒），默认 30000；<=0 表示不超时 */
  timeout?: number;
  /** 连接层错误（网络异常 / 超时）自动重试次数，默认 0；不重试 4xx/5xx */
  retries?: number;
}

/**
 * 轻量 fetch 封装，统一处理以下通用逻辑：
 *
 * - URL 前缀：相对路径自动补全 `/wiki-agent/api` 前缀
 * - 认证头：自动注入 `Authorization` 请求头
 * - Content-Type：POST/PUT/PATCH 自动添加 `application/json`（可被显式 headers 覆盖）
 * - 401 拦截：自动清除 Token → 跳转 `/login` → 抛出 UnauthorizedError
 * - 超时：默认 30s（AbortController），避免后端慢查询导致前端无限等待（B2）
 * - 重试：仅对网络异常 / 超时重试，不重试业务错误（4xx/5xx）
 *
 * @param path    请求路径（相对路径如 `/agent/capabilities`，或绝对 URL）
 * @param options 请求配置（headers 可与默认认证头合并，调用方显式值优先）
 * @returns Response 对象（401 场景不会返回，直接抛错）
 */
export async function request(path: string, options: RequestOptions = {}): Promise<Response> {
  const {
    headers: rawHeaders,
    method,
    timeout = 30000,
    retries = 0,
    signal,
    ...restOptions
  } = options;

  // 合并请求头：认证头为底层默认值，调用方显式 headers 可覆盖
  const headers: Record<string, string> = {
    ...getAuthHeaders(),
    ...(rawHeaders as Record<string, string> | undefined),
  };

  // POST/PUT/PATCH 自动补 Content-Type（如调用方未显式设置）
  const upperMethod = (method ?? 'GET').toUpperCase();
  if (['POST', 'PUT', 'PATCH'].includes(upperMethod)) {
    headers['Content-Type'] ??= 'application/json';
  }

  let lastError: unknown;
  for (let attempt = 0; attempt <= retries; attempt++) {
    const controller = new AbortController();
    const timer = timeout > 0 ? setTimeout(() => controller.abort(), timeout) : null;
    // 合并调用方 signal 与超时 signal（AbortSignal.any 不可用时退化为超时控制）
    const combined: AbortSignal =
      signal && typeof (AbortSignal as { any?: unknown }).any === 'function'
        ? (AbortSignal as unknown as { any(s: AbortSignal[]): AbortSignal }).any([
            signal,
            controller.signal,
          ])
        : controller.signal;
    try {
      const response = await fetch(buildUrl(path), {
        method,
        headers,
        ...restOptions,
        signal: combined,
      });
      if (timer) clearTimeout(timer);
      if (response.status === 401) {
        handleUnauthorized();
      }
      return response;
    } catch (err) {
      if (timer) clearTimeout(timer);
      lastError = err;
      // 仅网络异常（TypeError）或超时（AbortError）可重试
      const retryable = err instanceof TypeError || (err as Error)?.name === 'AbortError';
      if (attempt < retries && retryable) continue;
      throw err;
    }
  }
  throw lastError;
}
