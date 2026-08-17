import { watch, nextTick, type Ref } from 'vue';
import { useChatStore } from '@/stores/chat';

export function useAutoScroll(messagesContainer: Ref<HTMLElement | null>) {
  const chatStore = useChatStore();

  function scrollToBottom() {
    if (messagesContainer.value) {
      messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight;
    }
  }

  watch(
    () => chatStore.currentMessages.length,
    async () => {
      await nextTick();
      scrollToBottom();
    },
  );

  return { scrollToBottom };
}
