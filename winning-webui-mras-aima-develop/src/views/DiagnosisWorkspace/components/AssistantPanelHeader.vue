<script setup lang="ts">
import type { AssistantConversationSummary } from '@/types/diagnosis';
import AssistantHistoryList from '@/views/DiagnosisWorkspace/components/AssistantHistoryList.vue';

defineProps<{
  historyLoading: boolean;
  historyTotal: number;
  histories: AssistantConversationSummary[];
  selectedId?: string;
}>();

const emit = defineEmits<{ select: [item: AssistantConversationSummary] }>();
const historyOpen = defineModel<boolean>({ required: true });

function selectHistory(item: AssistantConversationSummary) {
  emit('select', item);
  historyOpen.value = false;
}
</script>

<template>
  <div class="d-flex align-center justify-space-between ga-3 mb-3">
    <div class="d-flex align-center ga-2">
      <v-icon icon="mdi-shield-search" color="primary" />
      <span class="text-body-large font-weight-medium">AI 排查助手</span>
    </div>
    <v-menu v-model="historyOpen" location="bottom end" :close-on-content-click="false" :offset="8">
      <template #activator="{ props: activatorProps }">
        <v-btn
          v-bind="activatorProps"
          :variant="historyOpen ? 'tonal' : 'text'"
          color="primary"
          size="small"
          rounded="pill"
          class="text-label-medium"
          :append-icon="historyOpen ? 'mdi-chevron-up' : 'mdi-chevron-down'"
          :loading="historyLoading"
        >
          历史对话 {{ historyTotal }}
        </v-btn>
      </template>

      <v-card variant="outlined" class="assistant-history-menu" elevation="4">
        <div class="px-4 py-3 text-title-small">历史对话</div>
        <AssistantHistoryList
          :items="histories"
          :loading="historyLoading"
          :selected-id="selectedId"
          @select="selectHistory"
        />
      </v-card>
    </v-menu>
  </div>

  <v-divider class="mb-3" />
</template>

<style scoped>
.assistant-history-menu {
  width: min(520px, calc(100vw - 32px));
  max-height: min(520px, calc(100vh - 96px));
  overflow-y: auto;
}
</style>
