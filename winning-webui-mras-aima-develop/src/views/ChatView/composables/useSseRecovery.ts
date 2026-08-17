import { getSessionMessages } from '@/services/chat';
import { useChatStore } from '@/stores/chat';
import { MESSAGE_STATUS } from '@/types/chat';

/**
 * SSE 断线后轮询恢复机制（文档 §7.2）
 *
 * 当 SSE 流异常关闭但已收到 traceId 或批次卡片时，
 * 每 2 秒轮询 GET /api/agent/sessions/{id}/messages，
 * 等待后端将运行结果持久化到消息列表，最长等待 30 分钟。
 */
export function useSseRecovery() {
  const chatStore = useChatStore();

  const pollTimers = new Map<string, ReturnType<typeof setInterval>>();

  /** 轮询间隔（毫秒） */
  const POLL_INTERVAL_MS = 2000;

  /** 最大等待时间（毫秒）= 30 分钟 */
  const MAX_WAIT_MS = 30 * 60 * 1000;

  /**
   * 开始轮询指定会话
   * @param sessionId 会话 ID
   * @param assistantMessageId 当前 running 状态的 Assistant 消息 ID
   */
  function startPolling(sessionId: string, assistantMessageId: string) {
    // 避免重复轮询
    if (pollTimers.has(sessionId)) return;

    const startTime = Date.now();

    const timer = setInterval(async () => {
      // 超时检查
      if (Date.now() - startTime > MAX_WAIT_MS) {
        stopPolling(sessionId);
        updateMessage(assistantMessageId, {
          status: 'error',
          errorMessage: '后台计算等待超时，请稍后从历史对话重新打开结果。',
        });
        return;
      }

      try {
        const messages = await getSessionMessages(sessionId);
        // 找到对应消息：最后一条 assistant 消息即为本轮运行结果
        const lastAssistant = [...messages].reverse().find((m) => m.role === 'assistant');
        if (lastAssistant) {
          stopPolling(sessionId);
          // 重新加载会话消息以刷新 UI
          await chatStore.switchSession(sessionId);
          return;
        }
      } catch {
        // 轮询失败静默继续，不中断循环
      }
    }, POLL_INTERVAL_MS);

    pollTimers.set(sessionId, timer);
  }

  /**
   * 停止轮询指定会话
   */
  function stopPolling(sessionId: string) {
    const timer = pollTimers.get(sessionId);
    if (timer) {
      clearInterval(timer);
      pollTimers.delete(sessionId);
    }
  }

  /**
   * 更新消息状态（通过 store 内部查找）
   *
   * 注意：此处直接查找 sessions 中的消息做更新，
   * 因为 polling 发生在 SSE 回调之外，store 的 updateAssistantMessage 是私有函数。
   */
  function updateMessage(messageId: string, updates: { status?: string; errorMessage?: string }) {
    const session = chatStore.currentSession;
    if (!session) return;

    const message = session.messages.find((m) => m.id === messageId);
    if (!message) return;

    if (updates.status) {
      message.status = updates.status as (typeof MESSAGE_STATUS)[keyof typeof MESSAGE_STATUS];
    }
    if (updates.errorMessage) {
      message.errorMessage = updates.errorMessage;
    }
  }

  /**
   * 清理所有轮询定时器
   */
  function cleanup() {
    pollTimers.forEach((timer) => clearInterval(timer));
    pollTimers.clear();
  }

  return {
    startPolling,
    stopPolling,
    cleanup,
  };
}
