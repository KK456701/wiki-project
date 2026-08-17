import { ref, watch } from 'vue';
import { useChatStore } from '@/stores/chat';
import type { Clarification } from '@/types/chat';

export function useClarification() {
  const chatStore = useChatStore();

  const showClarification = ref(false);
  const currentClarification = ref<Clarification | null>(null);
  const currentClarificationMessageId = ref<string | null>(null);

  // 监听最后一条消息的澄清状态
  watch(
    () => {
      const messages = chatStore.currentMessages;
      if (messages.length === 0) return null;
      const lastMsg = messages[messages.length - 1];
      return lastMsg.clarification && !lastMsg.clarificationStatus ? lastMsg.clarification : null;
    },
    (clarification) => {
      if (clarification) {
        currentClarification.value = clarification;
        currentClarificationMessageId.value =
          chatStore.currentMessages[chatStore.currentMessages.length - 1].id;
        showClarification.value = true;
      }
    },
  );

  function confirmClarification(selectedValues: string[]) {
    if (currentClarificationMessageId.value) {
      chatStore.handleClarification(currentClarificationMessageId.value, selectedValues);
    }
    showClarification.value = false;
    currentClarification.value = null;
    currentClarificationMessageId.value = null;
  }

  function dismissClarification() {
    if (currentClarificationMessageId.value) {
      chatStore.dismissClarification(currentClarificationMessageId.value);
    }
    showClarification.value = false;
    currentClarification.value = null;
    currentClarificationMessageId.value = null;
  }

  return {
    showClarification,
    currentClarification,
    confirmClarification,
    dismissClarification,
  };
}
