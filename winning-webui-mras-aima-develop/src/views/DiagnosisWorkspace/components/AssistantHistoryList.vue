<script setup lang="ts">
import { format } from 'date-fns/format';
import type { AssistantConversationSummary } from '@/types/diagnosis';

defineProps<{
  items: AssistantConversationSummary[];
  loading: boolean;
  selectedId?: string;
}>();

const emit = defineEmits<{ select: [item: AssistantConversationSummary] }>();

function time(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '' : format(date, 'MM-dd HH:mm');
}

function typeLabel(type: string): string {
  return type === 'AUTONOMOUS' ? '自主排查' : '患者澄清';
}
</script>

<template>
  <div class="assistant-history-list">
    <v-skeleton-loader v-if="loading" type="list-item-two-line@3" />
    <div v-else-if="items.length === 0" class="pa-4 text-body-medium text-medium-emphasis">
      暂无历史对话。完成患者澄清或自主排查后会显示在这里。
    </div>
    <button
      v-for="item in items"
      v-else
      :key="item.conversationId"
      type="button"
      class="assistant-history-item"
      :class="{ 'is-selected': selectedId === item.conversationId }"
      @click="emit('select', item)"
    >
      <span class="assistant-history-main">
        <strong>{{ item.title }}</strong>
        <small>{{ item.preview || '暂无摘要' }}</small>
      </span>
      <span class="assistant-history-meta">
        <span>{{ typeLabel(item.type) }}</span>
        <time>{{ time(item.updatedAt) }}</time>
      </span>
    </button>
  </div>
</template>

<style lang="scss" scoped>
.assistant-history-list {
  border-top: 1px solid rgba(var(--v-border-color), var(--v-border-opacity));
}

.assistant-history-item {
  display: flex;
  width: 100%;
  gap: 16px;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  color: rgb(var(--v-theme-on-surface));
  text-align: left;
  background: rgb(var(--v-theme-surface));
  border: 0;
  border-bottom: 1px solid rgba(var(--v-border-color), var(--v-border-opacity));
  cursor: pointer;

  &:hover,
  &.is-selected {
    background: rgba(var(--v-theme-primary), 0.06);
  }
}

.assistant-history-main {
  min-width: 0;

  strong,
  small {
    display: block;
  }

  strong {
    color: rgba(var(--v-theme-on-surface), 0.87);
    font-size: 14px;
    font-weight: 500;
    line-height: 20px;
  }

  small {
    margin-top: 3px;
    overflow: hidden;
    color: rgba(var(--v-theme-on-surface), 0.6);
    font-size: 14px;
    line-height: 20px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.assistant-history-meta {
  flex: none;
  color: rgba(var(--v-theme-on-surface), 0.56);
  font-size: 12px;
  text-align: right;

  span,
  time {
    display: block;
  }
}
</style>
