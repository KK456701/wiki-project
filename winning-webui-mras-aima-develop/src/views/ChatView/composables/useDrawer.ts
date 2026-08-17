import { ref, watch } from 'vue';
import { useDisplay } from 'vuetify';
import { useChatStore } from '@/stores/chat';

export function useDrawer() {
  const { lgAndUp } = useDisplay();
  const chatStore = useChatStore();
  const drawerOpen = ref(lgAndUp.value);

  watch(lgAndUp, (isDesktop) => {
    drawerOpen.value = isDesktop;
  });

  watch(drawerOpen, (isOpen) => {
    if (isOpen) {
      chatStore.loadSessions();
    }
  });

  function toggleDrawer() {
    drawerOpen.value = !drawerOpen.value;
  }

  return { drawerOpen, lgAndUp, toggleDrawer };
}
