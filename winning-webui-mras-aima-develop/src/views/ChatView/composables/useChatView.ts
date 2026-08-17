import { onMounted, onUnmounted, ref, watch } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { useChatStore } from '@/stores/chat';
import { useSseRecovery } from './useSseRecovery';

export function useChatView() {
  const chatStore = useChatStore();
  const router = useRouter();
  const route = useRoute();
  const { startPolling, cleanup: cleanupPolling } = useSseRecovery();

  const showError = ref(false);
  const errorMessage = ref('');

  // 初始加载标志：组件挂载后为 true，onMounted 完成后置为 false
  // 用于防止空状态在页面初始化时闪烁（如直接访问 /chat/{sessionId}）
  const isInitialLoading = ref(true);

  // 删除确认状态
  const deleteConfirmVisible = ref(false);
  const deleteTargetSessionId = ref<string | null>(null);

  function requestDelete(sessionId: string) {
    deleteTargetSessionId.value = sessionId;
    deleteConfirmVisible.value = true;
  }

  async function confirmDelete() {
    if (!deleteTargetSessionId.value) return;

    const result = await chatStore.deleteSession(deleteTargetSessionId.value);
    deleteConfirmVisible.value = false;
    deleteTargetSessionId.value = null;

    if (!result.success) {
      errorMessage.value = result.error || '删除对话失败';
      showError.value = true;
    }
  }

  function cancelDelete() {
    deleteConfirmVisible.value = false;
    deleteTargetSessionId.value = null;
  }

  /** 点击侧边栏对话：导航到 /chat/:sessionId */
  function selectSession(sessionId: string) {
    router.push({ name: 'Chat', params: { sessionId } });
  }

  /**
   * 新建对话：仅导航到 /chat，不操作 store。
   *
   * 路由→store watcher 检测到 route.params.sessionId 为空时，
   * 会自动调用 chatStore.resetToNewChat() 清理状态。
   * 后端会话仅在用户发送第一条消息时惰性创建（见 store.sendMessage）。
   *
   * 注意：不要在此处调用 resetToNewChat()——store→route 同步 watcher
   * 会立即检测到 currentSessionId 变为 null，但此时 route 尚未更新，
   * 导致 watcher 误以为需要同步而跳转到 /chat/{oldSessionId}。
   */
  function handleCreateSession() {
    router.push({ name: 'Chat' });
  }

  // 路由参数变化 → 驱动 store 对话切换
  watch(
    () => route.params.sessionId as string | undefined,
    (sessionId) => {
      // 幂等守卫：避免与 store→route watcher 形成循环
      if (sessionId && sessionId !== chatStore.currentSessionId) {
        chatStore.switchSession(sessionId).then((result) => {
          if (!result.success) {
            errorMessage.value = result.error || '切换对话失败';
            showError.value = true;
            // sessionId 无效 → 跳转到 /chat
            chatStore.resetToNewChat();
            router.replace({ name: 'Chat' });
          }
        });
      } else if (!sessionId && chatStore.currentSessionId) {
        chatStore.resetToNewChat();
      }
    },
  );

  // store 中 currentSessionId 变化 → 同步到路由
  watch(
    () => chatStore.currentSessionId,
    (newId) => {
      const routeId = route.params.sessionId as string | undefined;
      if (newId !== (routeId || null)) {
        router.replace(newId ? { name: 'Chat', params: { sessionId: newId } } : { name: 'Chat' });
      }
    },
  );

  onMounted(async () => {
    // 注入 SSE 断线回调
    chatStore.setSseDisconnectCallback((sessionId, assistantMessageId) => {
      startPolling(sessionId, assistantMessageId);
    });

    const sessionsResult = await chatStore.loadSessions();
    if (!sessionsResult.success) {
      errorMessage.value = sessionsResult.error || '加载对话列表失败';
      showError.value = true;
    }

    const modelsResult = await chatStore.loadModels();
    if (!modelsResult.success) {
      errorMessage.value = errorMessage.value
        ? `${errorMessage.value}；${modelsResult.error}`
        : modelsResult.error || '加载模型列表失败';
      showError.value = true;
    }

    // 根据初始路由参数决定对话状态
    const initialSessionId = route.params.sessionId as string | undefined;
    if (initialSessionId) {
      const result = await chatStore.switchSession(initialSessionId);
      if (!result.success) {
        errorMessage.value = result.error || '切换对话失败';
        showError.value = true;
        // sessionId 无效 → 跳转到 /chat
        chatStore.resetToNewChat();
        router.replace({ name: 'Chat' });
      }
    }

    isInitialLoading.value = false;
  });

  // 组件卸载时清理所有轮询定时器
  onUnmounted(() => {
    cleanupPolling();
  });

  return {
    showError,
    errorMessage,
    isInitialLoading,
    deleteConfirmVisible,
    requestDelete,
    confirmDelete,
    cancelDelete,
    selectSession,
    handleCreateSession,
  };
}
