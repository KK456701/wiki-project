import { formatISO, getTime } from 'date-fns';
import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import type {
  ChatSession,
  ChatMessage,
  MessageResponse,
  SseEvent,
  StageInfo,
  ModelInfo,
  TraceNode,
  SessionResponse,
  BatchResultItem,
  WorkspaceRedirect,
} from '@/types/chat';
import {
  SSE_EVENT,
  MESSAGE_ROLE,
  MESSAGE_STATUS,
  CLARIFICATION_STATUS,
  AGENT_STATUS,
  STOP_REASON,
} from '@/types/chat';
import {
  sendChatStream,
  getAgentCapabilities,
  getSessions,
  getSessionMessages,
  createSession,
  deleteSession as deleteSessionApi,
  type ChatRequest,
} from '@/services/chat';

export const useChatStore = defineStore('chat', () => {
  // 对话列表
  const sessions = ref<ChatSession[]>([]);
  // 当前对话 ID（前端本地 ID，用于 UI 展示）
  const currentSessionId = ref<string | null>(null);
  // 后端返回的对话 ID（用于 API 调用）
  const backendSessionId = ref<string | null>(null);
  // 是否正在加载对话消息
  const loadingSessionMessages = ref(false);
  // 是否正在流式响应
  const isStreaming = ref(false);
  const pendingWorkspaceRedirect = ref<WorkspaceRedirect | null>(null);
  // 当前正在流式响应的 sessionId（用于按 session 隔离流式状态）
  const activeStreamSessionId = ref<string | null>(null);
  // 当前 AbortController
  let currentAbortController: AbortController | null = null;
  /** SSE 异常断开时的轮询回调（由 useChatView 注入） */
  let onSseDisconnect: ((sessionId: string, assistantMessageId: string) => void) | null = null;
  // 消息 ID 计数器，确保同一毫秒内生成的 ID 不重复
  let messageCounter = 0;

  // 模型列表
  const models = ref<ModelInfo[]>([]);
  // 当前选中的模型 ID
  const currentModelId = ref<string | null>(null);
  // 是否已加载模型列表
  const modelsLoaded = ref(false);
  // 是否已加载对话列表
  const sessionsLoaded = ref(false);
  // 是否正在加载对话列表
  const loadingSessions = ref(false);

  // 当前对话
  const currentSession = computed(() => {
    return sessions.value.find((s) => s.id === currentSessionId.value) || null;
  });

  // 当前对话的消息列表
  const currentMessages = computed(() => {
    return currentSession.value?.messages || [];
  });

  /**
   * 后台调用 POST /api/agent/sessions 创建真实后端会话
   * @returns 后端返回的 sessionId，失败返回 null
   */
  async function createNewSession(): Promise<string | null> {
    try {
      const { sessionId } = await createSession();
      backendSessionId.value = sessionId;

      const session: ChatSession = {
        id: sessionId,
        title: '新对话',
        messages: [],
        createdAt: Date.now(),
        updatedAt: Date.now(),
      };
      sessions.value.unshift(session);
      currentSessionId.value = sessionId;
      return sessionId;
    } catch {
      return null;
    }
  }

  /**
   * 后端对话创建回调：用后端返回的真实 session_id 替换占位 ID
   *
   * 在 SSE agent_start 事件中调用，确保 sessions[] 中存储的始终是后端 ID。
   * 作为 createPendingSession 的兜底：如果后端会话创建失败但 sendMessage 已创建占位 session，SSE 仍可修复。
   */
  function onSessionCreated(realSessionId: string) {
    if (!backendSessionId.value) {
      backendSessionId.value = realSessionId;
    }

    if (currentSessionId.value && currentSessionId.value !== realSessionId) {
      const session = sessions.value.find((s) => s.id === currentSessionId.value);
      if (session) {
        session.id = realSessionId;
      }
      currentSessionId.value = realSessionId;
    }
  }

  /**
   * 创建临时占位对话（兜底：仅在后端 createNewSession 失败时使用）
   *
   * 创建的对话使用前端生成临时 ID，待 SSE agent_start 事件返回真实 session_id 后，
   * 由 onSessionCreated() 替换为后端 ID。
   */
  function createPendingSession(): string {
    const sessionId = `session_${Date.now()}`;
    const session: ChatSession = {
      id: sessionId,
      title: '新对话',
      messages: [],
      createdAt: Date.now(),
      updatedAt: Date.now(),
    };
    sessions.value.unshift(session);
    currentSessionId.value = sessionId;
    return sessionId;
  }

  /**
   * 重置到新对话状态（不创建对话记录）
   *
   * 仅清空当前选中的对话，右侧对话主区域回到初始空状态。
   * 对话记录在用户发送第一条消息时通过 addUserMessage() 惰性创建。
   */
  function resetToNewChat() {
    currentSessionId.value = null;
    backendSessionId.value = null;
  }

  /**
   * 切换对话（异步：必要时加载消息列表）
   * @returns 加载结果
   */
  async function switchSession(sessionId: string): Promise<{ success: boolean; error?: string }> {
    loadingSessionMessages.value = true;
    currentSessionId.value = sessionId;

    const session = sessions.value.find((s) => s.id === sessionId);
    if (!session) {
      loadingSessionMessages.value = false;
      return { success: false, error: '对话不存在' };
    }

    // 每次切换对话都从后端重新查询消息，确保数据实时
    const result = await loadSessionMessages(sessionId);
    loadingSessionMessages.value = false;
    return result;
  }

  /**
   * 删除对话（异步：调后端接口后移除本地）
   * @returns 删除结果
   */
  async function deleteSession(sessionId: string): Promise<{ success: boolean; error?: string }> {
    try {
      await deleteSessionApi(sessionId);
    } catch (error) {
      // eslint-disable-next-line no-console
      console.error('删除对话失败:', error);
      const errorMessage = error instanceof Error ? error.message : '未知错误';
      return { success: false, error: `删除对话失败: ${errorMessage}` };
    }

    const isCurrentDeleted = currentSessionId.value === sessionId;
    const index = sessions.value.findIndex((s) => s.id === sessionId);
    if (index !== -1) {
      sessions.value.splice(index, 1);
    }

    // 若删除的是当前对话，切换到列表第一个对话并加载其消息
    if (isCurrentDeleted) {
      const fallbackId = sessions.value[0]?.id || null;
      if (fallbackId) {
        return switchSession(fallbackId);
      }
      currentSessionId.value = null;
    }

    return { success: true };
  }

  /**
   * 添加用户消息
   */
  function addUserMessage(content: string): string {
    if (!currentSessionId.value) {
      createPendingSession();
    }

    const session = currentSession.value;
    if (!session) return '';

    const messageId = `msg_${Date.now()}_${++messageCounter}`;
    const message: ChatMessage = {
      id: messageId,
      role: MESSAGE_ROLE.USER,
      content,
      status: MESSAGE_STATUS.COMPLETED,
      stages: [],
      traceNodes: [],
      createdAt: Date.now(),
    };

    session.messages.push(message);
    session.updatedAt = Date.now();

    // 如果是第一条消息，更新对话标题
    if (session.messages.length === 1) {
      session.title = content.slice(0, 20) + (content.length > 20 ? '...' : '');
    }

    return messageId;
  }

  /**
   * 添加助手消息（占位）
   */
  function addAssistantMessage(): string {
    const session = currentSession.value;
    if (!session) return '';

    const messageId = `msg_${Date.now()}_${++messageCounter}`;
    const message: ChatMessage = {
      id: messageId,
      role: MESSAGE_ROLE.ASSISTANT,
      content: '',
      status: MESSAGE_STATUS.STREAMING,
      stages: [],
      traceNodes: [],
      createdAt: Date.now(),
    };

    session.messages.push(message);
    return messageId;
  }

  /**
   * 更新助手消息
   */
  function updateAssistantMessage(messageId: string, updates: Partial<ChatMessage>) {
    const session = currentSession.value;
    if (!session) return;

    const message = session.messages.find((m) => m.id === messageId);
    if (message) {
      Object.assign(message, updates);
    }
  }

  /**
   * 处理 SSE 事件
   */
  function handleSseEvent(messageId: string, event: SseEvent) {
    const session = currentSession.value;
    if (!session) return;

    const message = session.messages.find((m) => m.id === messageId);
    if (!message) return;

    // 任意事件携带的 session_id 均可用于替换前端占位 ID（每个事件都会携带）
    if (event.sessionId) {
      onSessionCreated(event.sessionId);
    }

    switch (event.event) {
      case SSE_EVENT.AGENT_START:
        message.traceId = event.traceId;
        break;

      case SSE_EVENT.TRACE_NODE: {
        const node: TraceNode = {
          nodeId: event.nodeId,
          nodeName: event.nodeName,
          nodeTitle: event.nodeTitle,
          nodeType: event.nodeType,
          status: event.status,
          startedAt: formatISO(event.startedAtEpochMs),
          endedAt: event.endedAtEpochMs ? formatISO(event.endedAtEpochMs) : undefined,
          durationMs: event.durationMs,
          subtaskId: event.subtaskId,
          inputData: event.input,
          outputData: event.output,
          processingSummary: event.processingSummary,
          errorCode: event.errorCode,
          errorMessage: event.errorMessage,
        };
        message.traceNodes.push(node);
        break;
      }

      case SSE_EVENT.MODEL_START:
        message.currentStage = event.message;
        break;

      case SSE_EVENT.TOOL_CALL: {
        const stage: StageInfo = {
          step: event.step,
          toolName: event.toolName,
          message: '工具调用中...',
          status: 'running',
        };
        message.stages.push(stage);
        message.currentStage = `正在执行: ${event.toolName}`;
        break;
      }

      case SSE_EVENT.TOOL_RESULT: {
        const stage = message.stages.find((s) => s.step === event.step);
        if (stage) {
          stage.status = event.status;
          stage.message = event.message;
          stage.durationMs = event.durationMs;
        }
        break;
      }

      case SSE_EVENT.STAGE_UPDATE:
        message.currentStage = event.message;
        if (event.durationMs != null) {
          message.currentStageDurationMs = event.durationMs;
        }
        if (event.subtaskId) {
          message.currentSubtaskId = event.subtaskId;
        }
        break;

      case SSE_EVENT.ASSISTANT_MESSAGE:
        message.content = event.message;
        message.stepCount = event.step;
        break;

      case SSE_EVENT.WORKSPACE_REDIRECT:
        pendingWorkspaceRedirect.value = {
          workspace: event.workspace,
          step: event.step,
          mode: event.mode,
          ruleId: event.ruleId,
          ruleName: event.ruleName,
          profileId: event.profileId,
          statStart: event.statStart,
          statEnd: event.statEnd,
          candidateIndicators: event.candidateIndicators,
        };
        break;

      case SSE_EVENT.BATCH_INDICATOR_RESULT: {
        const item: BatchResultItem = {
          ruleId: event.ruleId,
          ruleName: event.ruleName,
          status: event.status,
          done: event.done,
          total: event.total,
          qualityStatus: event.qualityStatus ?? 'NORMAL',
          batchRunId: event.batchRunId,
          profileId: event.profileId,
          profileLabel: event.profileLabel,
          resultValue: event.resultValue,
          numeratorCount: event.numeratorCount,
          denominatorCount: event.denominatorCount,
          sampleCount: event.sampleCount,
          unit: event.unit,
          targetValue: event.targetValue,
          targetDirection: event.targetDirection as BatchResultItem['targetDirection'],
          calculationDisplay: event.calculationDisplay,
          statStart: event.statStart,
          statEnd: event.statEnd,
          runId: event.runId,
          dataFreshness: event.dataFreshness,
          detailKind: event.detailKind,
          detailContractVersion: event.detailContractVersion,
          overviewSqlHash: event.overviewSqlHash,
          errorCode: event.errorCode,
          errorMessage: event.errorMessage,
        };

        if (!message.batchResults) {
          message.batchResults = [];
        }

        // 按 (ruleId, profileId) 去重替换
        const key = `${item.ruleId}::${item.profileId ?? ''}`;
        const existingIndex = message.batchResults.findIndex(
          (r) => `${r.ruleId}::${r.profileId ?? ''}` === key,
        );
        if (existingIndex >= 0) {
          message.batchResults[existingIndex] = item;
        } else {
          message.batchResults.push(item);
        }
        break;
      }

      case SSE_EVENT.AGENT_DONE: {
        const isCompleted = event.status === AGENT_STATUS.COMPLETED;
        const isClarification =
          event.stopReason === STOP_REASON.CLARIFICATION || message.clarification != null;

        if (isCompleted || isClarification) {
          message.status = MESSAGE_STATUS.COMPLETED;
          if (isClarification && !message.content) {
            message.content = event.message || '等待你选择';
          }
        } else {
          message.status = MESSAGE_STATUS.ERROR;
          message.errorMessage = event.message || '运行失败';
        }

        message.stepCount = event.stepCount;
        message.currentStage = undefined;

        // 内容为空且无批量结果时显示兜底文案
        if (!message.content && !message.batchResults?.length) {
          message.content = '本轮处理已结束，但没有返回可展示的业务回答。';
        }

        isStreaming.value = false;
        activeStreamSessionId.value = null;
        break;
      }

      case SSE_EVENT.AGENT_ERROR:
        message.status = MESSAGE_STATUS.ERROR;
        message.errorMessage = event.message;
        message.currentStage = undefined;
        isStreaming.value = false;
        activeStreamSessionId.value = null;
        break;

      case SSE_EVENT.CLARIFICATION_REQUIRED:
        message.clarification = event.clarification;
        // 写入事件携带的 message 字段到回答内容
        message.content = event.message || '';
        message.status = MESSAGE_STATUS.COMPLETED;
        message.currentStage = undefined;
        isStreaming.value = false;
        activeStreamSessionId.value = null;
        break;
    }
  }

  /**
   * 将 API 消息响应转换为前端 ChatMessage
   */
  function mapMessageResponse(m: MessageResponse): ChatMessage {
    const createdAtMs = getTime(new Date(m.createdAt));
    return {
      id: `msg_${createdAtMs}_${++messageCounter}`,
      role: m.role,
      content: m.content,
      status: MESSAGE_STATUS.COMPLETED,
      ruleId: m.ruleId ?? undefined,
      ruleName: m.ruleName ?? undefined,
      statStart: m.statStart ?? undefined,
      statEnd: m.statEnd ?? undefined,
      runId: m.runId ?? undefined,
      stages: [],
      traceNodes: [],
      batchResults: m.batchResults ?? undefined,
      createdAt: createdAtMs,
    };
  }

  /**
   * 从后端加载指定对话的消息列表
   * @returns 加载结果
   */
  async function loadSessionMessages(
    sessionId: string,
  ): Promise<{ success: boolean; error?: string }> {
    const session = sessions.value.find((s) => s.id === sessionId);
    if (!session) {
      return { success: false, error: '对话不存在' };
    }

    try {
      const list = await getSessionMessages(sessionId);
      session.messages = list.map(mapMessageResponse);
      backendSessionId.value = sessionId;
      return { success: true };
    } catch (error) {
      // eslint-disable-next-line no-console
      console.error('加载对话消息失败:', error);
      const errorMessage = error instanceof Error ? error.message : '未知错误';
      return { success: false, error: `加载对话消息失败: ${errorMessage}` };
    }
  }

  /**
   * 将 API 对话响应转换为前端 ChatSession
   */
  function mapSessionResponse(s: SessionResponse): ChatSession {
    const lastMessageAtMs = getTime(new Date(s.lastMessageAt));
    return {
      id: s.sessionId,
      title: s.title,
      messages: [],
      messageCount: s.messageCount,
      createdAt: lastMessageAtMs,
      updatedAt: lastMessageAtMs,
    };
  }

  /**
   * 从后端加载对话列表（每次调用都实时查询后端）
   * @returns 加载结果，包含成功状态和错误信息
   */
  async function loadSessions(): Promise<{ success: boolean; error?: string }> {
    loadingSessions.value = true;
    try {
      const list = await getSessions();
      // 合并刷新：保留已有对话的 messages，避免覆盖已加载的消息数据
      const existingMap = new Map(sessions.value.map((s) => [s.id, s]));
      sessions.value = list.map((s) => {
        const existing = existingMap.get(s.sessionId);
        if (existing) {
          return {
            ...mapSessionResponse(s),
            messages: existing.messages,
          };
        }
        return mapSessionResponse(s);
      });
      sessionsLoaded.value = true;
      return { success: true };
    } catch (error) {
      // eslint-disable-next-line no-console
      console.error('加载对话列表失败:', error);
      const errorMessage = error instanceof Error ? error.message : '未知错误';
      return { success: false, error: `加载对话列表失败: ${errorMessage}` };
    } finally {
      loadingSessions.value = false;
    }
  }

  /**
   * 加载可用模型列表
   * @returns 加载结果，包含成功状态和错误信息
   */
  async function loadModels(): Promise<{ success: boolean; error?: string }> {
    if (modelsLoaded.value) return { success: true };

    try {
      const capabilities = await getAgentCapabilities();
      models.value = capabilities.models.filter((m) => m.available);
      if (capabilities.defaultModel) {
        currentModelId.value = capabilities.defaultModel;
      } else if (models.value.length > 0) {
        currentModelId.value = models.value[0].id;
      }
      modelsLoaded.value = true;
      return { success: true };
    } catch (error) {
      // eslint-disable-next-line no-console
      console.error('加载模型列表失败:', error);
      const errorMessage = error instanceof Error ? error.message : '未知错误';
      return { success: false, error: `加载模型列表失败: ${errorMessage}` };
    }
  }

  /**
   * 切换模型
   */
  function switchModel(modelId: string) {
    currentModelId.value = modelId;
  }

  /**
   * 注入 SSE 断线回调（由 useChatView 调用）
   */
  function setSseDisconnectCallback(cb: (sessionId: string, assistantMessageId: string) => void) {
    onSseDisconnect = cb;
  }

  /**
   * 发送消息
   */
  async function sendMessage(
    content: string,
    fileKey?: string,
    clarificationResponse?: ChatRequest['clarificationResponse'],
  ) {
    if (!content.trim() || isStreaming.value) return;

    pendingWorkspaceRedirect.value = null;

    // 确保有后端会话 ID
    if (!backendSessionId.value) {
      const newId = await createNewSession();
      if (!newId) {
        // 后端创建失败，使用占位会话作为兜底
        createPendingSession();
      }
    }

    // 添加用户消息
    addUserMessage(content);

    // 添加助手消息占位
    const assistantMessageId = addAssistantMessage();
    if (!assistantMessageId) return;

    // 构建请求 - 使用后端返回的 session_id
    const request: ChatRequest = {
      query: content,
      sessionId: backendSessionId.value || undefined,
      modelId: currentModelId.value || undefined,
      fileKey: fileKey,
      clarificationResponse,
    };

    isStreaming.value = true;
    activeStreamSessionId.value = currentSessionId.value;

    // 发送流式请求
    currentAbortController = sendChatStream(request, {
      onEvent: (event) => {
        handleSseEvent(assistantMessageId, event);
      },
      onError: (error) => {
        const session = currentSession.value;
        const msg = session?.messages.find((m) => m.id === assistantMessageId);

        // 已有 traceId 或批次卡片 → 进入轮询而非直接标记失败
        // AbortError 是用户主动停止，不触发轮询
        if (
          msg &&
          (msg.traceId || (msg.batchResults && msg.batchResults.length > 0)) &&
          !(error instanceof DOMException && error.name === 'AbortError')
        ) {
          updateAssistantMessage(assistantMessageId, {
            currentStage: '实时连接已结束，后台仍在继续计算，正在等待最终结果写入…',
          });
          // 触发轮询恢复
          if (onSseDisconnect && currentSessionId.value) {
            onSseDisconnect(currentSessionId.value, assistantMessageId);
          }
        } else {
          updateAssistantMessage(assistantMessageId, {
            status: MESSAGE_STATUS.ERROR,
            errorMessage: error.message,
          });
        }
        isStreaming.value = false;
        activeStreamSessionId.value = null;
      },
      onDone: () => {
        isStreaming.value = false;
        activeStreamSessionId.value = null;
      },
    });
  }

  /**
   * 停止流式响应
   */
  function stopStreaming() {
    if (currentAbortController) {
      currentAbortController.abort();
      currentAbortController = null;
    }

    // 将最后一条正在流式输出的助手消息标记为已停止
    const session = currentSession.value;
    if (session) {
      const messages = session.messages;
      for (let i = messages.length - 1; i >= 0; i--) {
        if (
          messages[i].role === MESSAGE_ROLE.ASSISTANT &&
          messages[i].status === MESSAGE_STATUS.STREAMING
        ) {
          messages[i].status = MESSAGE_STATUS.STOPPED;
          messages[i].currentStage = undefined;
          break;
        }
      }
    }

    isStreaming.value = false;
    activeStreamSessionId.value = null;
  }

  /**
   * 处理澄清选择
   */
  async function handleClarification(messageId: string, selectedValues: string[]) {
    const session = currentSession.value;
    if (!session) return;

    const message = session.messages.find((m) => m.id === messageId);
    if (!message || !message.clarification) return;

    const clarification = message.clarification;
    const continuation = `${clarification.resumePrefix}${selectedValues.join('、')}`;
    const selectedOptionIds = selectedValues
      .map((value) => clarification.options.find((option) => option.value === value)?.id)
      .filter((value): value is string => Boolean(value));
    const clarificationResponse =
      selectedOptionIds.length === selectedValues.length && selectedOptionIds.length > 0
        ? {
            clarificationId: clarification.clarificationId,
            selectedOptionIds,
            resumeToken: clarification.resumeToken,
          }
        : undefined;

    // 标记澄清已确认，保留 clarification 数据用于 UI 展示
    message.clarificationStatus = CLARIFICATION_STATUS.CONFIRMED;
    message.clarificationAnswer = selectedValues;

    // 发送后续请求
    await sendMessage(continuation, undefined, clarificationResponse);
  }

  /**
   * 忽略澄清（用户看到弹窗但未做选择就关闭了）
   */
  function dismissClarification(messageId: string) {
    const session = currentSession.value;
    if (!session) return;

    const message = session.messages.find((m) => m.id === messageId);
    if (!message || !message.clarification) return;

    message.clarificationStatus = CLARIFICATION_STATUS.DISMISSED;
  }

  function consumeWorkspaceRedirect(): WorkspaceRedirect | null {
    const value = pendingWorkspaceRedirect.value;
    pendingWorkspaceRedirect.value = null;
    return value;
  }

  return {
    sessions,
    currentSessionId,
    backendSessionId,
    currentSession,
    currentMessages,
    loadingSessionMessages,
    isStreaming,
    pendingWorkspaceRedirect,
    activeStreamSessionId,
    models,
    currentModelId,
    modelsLoaded,
    sessionsLoaded,
    loadingSessions,
    resetToNewChat,
    createNewSession,
    switchSession,
    loadSessionMessages,
    deleteSession,
    sendMessage,
    stopStreaming,
    handleClarification,
    dismissClarification,
    loadModels,
    loadSessions,
    switchModel,
    setSseDisconnectCallback,
    consumeWorkspaceRedirect,
  };
});
