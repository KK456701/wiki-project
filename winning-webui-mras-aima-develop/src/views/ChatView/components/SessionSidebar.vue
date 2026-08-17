<script setup lang="ts">
import { format } from 'date-fns';
import { computed } from 'vue';
import type { ChatSession } from '@/types/chat';

const props = defineProps<{
  sessions: ChatSession[];
  currentSessionId: string | null;
  loading?: boolean;
}>();

const emit = defineEmits<{
  select: [sessionId: string];
  create: [];
  delete: [sessionId: string];
}>();

const sortedSessions = computed(() => {
  return [...props.sessions].sort((a, b) => b.updatedAt - a.updatedAt);
});

function formatTime(timestamp: number): string {
  return format(new Date(timestamp), 'yyyy-MM-dd HH:mm');
}
</script>

<template>
  <div class="d-flex flex-column h-100">
    <div class="d-flex align-center pa-3 justify-space-between">
      <span class="text-title-medium text-high-emphasis">对话（共 {{ sessions.length }} 条）</span>
      <v-btn
        icon="mdi-chat-plus-outline"
        variant="text"
        color="primary"
        title="新对话"
        @click="emit('create')"
      />
    </div>

    <v-divider />

    <!-- 加载骨架屏 -->
    <div v-if="loading" class="flex-grow-1 overflow-hidden px-2 py-3">
      <div v-for="n in 5" :key="n" class="py-3 px-2 rounded-lg mb-1">
        <v-skeleton-loader type="text" class="mb-1" />
        <v-skeleton-loader type="text" width="60%" height="12" />
      </div>
    </div>

    <v-virtual-scroll
      v-else-if="sessions.length > 0"
      :items="sortedSessions"
      :item-height="64"
      class="flex-grow-1 pa-2"
    >
      <template #default="{ item }">
        <v-list-item
          :key="item.id"
          :active="item.id === currentSessionId"
          color="primary"
          class="session-item rounded-lg"
          @click="emit('select', item.id)"
        >
          <v-list-item-title
            :title="item.title"
            class="text-body-medium text-no-wrap text-truncate"
          >
            {{ item.title }}
          </v-list-item-title>

          <v-list-item-subtitle class="text-body-small">
            <span v-if="item.messageCount !== undefined" class="session-count">
              {{ item.messageCount }} 条消息 ·
            </span>
            <span>{{ formatTime(item.updatedAt) }}</span>
          </v-list-item-subtitle>

          <template #append>
            <v-btn
              icon="mdi-delete-outline"
              variant="text"
              size="small"
              color="error"
              title="删除对话"
              class="session-delete-btn"
              @click.stop="emit('delete', item.id)"
            />
          </template>
        </v-list-item>
      </template>
    </v-virtual-scroll>

    <!-- 空状态：水平垂直居中 -->
    <div v-else class="flex-grow-1 d-flex flex-column align-center justify-center">
      <v-icon icon="mdi-chat-outline" size="48" color="on-surface-variant" />
      <p class="text-medium-emphasis mt-2">暂无对话记录</p>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.session-count {
  color: rgba(var(--v-theme-primary), 0.8);
}

.session-delete-btn {
  display: none;
}

.session-item:hover .session-delete-btn {
  display: block;
}
</style>
