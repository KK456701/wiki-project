import type { SseEvent, SseEventType } from '@/types/chat';
import { request } from './request';

/**
 * 解析单个 SSE 事件块
 * @param block SSE 事件文本块（以双换行分隔）
 * @returns 解析后的事件对象，无效块返回 null
 */
export function parseSseBlock(block: string): SseEvent | null {
  let eventName = 'message';
  const dataLines: string[] = [];

  for (const line of block.split(/\r?\n/)) {
    if (line.startsWith('event:')) {
      eventName = line.slice(6).trim();
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).trimStart());
    }
  }

  if (dataLines.length === 0) return null;

  try {
    const payload = JSON.parse(dataLines.join('\n'));
    payload.event = eventName;
    return payload as SseEvent;
  } catch {
    // eslint-disable-next-line no-console
    console.warn('[SSE] 解析事件数据失败:', dataLines.join('\n'));
    return null;
  }
}

/**
 * SSE 事件回调
 */
export interface SseCallbacks {
  onEvent: (event: SseEvent) => void;
  onError?: (error: Error) => void;
  onDone?: () => void;
}

/**
 * 通过 request() + ReadableStream 读取 SSE 流
 *
 * 复用 request() 的 URL 前缀拼接、认证头注入、401 统一拦截。
 *
 * @param path   请求路径（相对路径如 /agent/chat/stream，自动补全 API_BASE 前缀）
 * @param body   请求体
 * @param callbacks 事件回调
 * @returns AbortController，可用于取消请求
 */
export function fetchSseStream(
  path: string,
  body: Record<string, unknown>,
  callbacks: SseCallbacks,
): AbortController {
  const controller = new AbortController();

  (async () => {
    try {
      const response = await request(path, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
        signal: controller.signal,
      });

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}: ${response.statusText}`);
      }

      const reader = response.body!.getReader();
      const decoder = new TextDecoder('utf-8');
      let buffer = '';

      while (true) {
        const chunk = await reader.read();
        if (chunk.done) break;

        buffer += decoder.decode(chunk.value, { stream: true });
        // 兼容 \r\n\r\n 分帧（文档 §5.1）
        buffer = buffer.replace(/\r\n/g, '\n');
        const blocks = buffer.split('\n\n');
        buffer = blocks.pop() || '';

        for (const block of blocks) {
          const trimmed = block.trim();
          if (!trimmed) continue;
          const event = parseSseBlock(trimmed);
          if (event) {
            callbacks.onEvent(event);
          }
        }
      }

      callbacks.onDone?.();
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') {
        callbacks.onDone?.();
        return;
      }
      callbacks.onError?.(error instanceof Error ? error : new Error(String(error)));
    }
  })();

  return controller;
}

/**
 * 获取事件类型（类型安全）
 */
export function getEventType(event: SseEvent): SseEventType {
  return event.event;
}
