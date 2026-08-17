<script setup lang="ts">
import { computed, type Directive } from 'vue';
import type { AssistantDisplayEvent } from '@/views/DiagnosisWorkspace/assistant-events';
import { useAssistantProcessTimeline } from '@/views/DiagnosisWorkspace/composables/useAssistantProcessTimeline';

const props = defineProps<{
  events: AssistantDisplayEvent[];
}>();

const events = computed(() => props.events);
const timeline = useAssistantProcessTimeline(events);
const vAutoScroll: Directive<HTMLElement, boolean> = {
  mounted(element, binding) {
    if (binding.value) element.scrollTop = element.scrollHeight;
  },
  updated(element, binding) {
    if (binding.value) element.scrollTop = element.scrollHeight;
  },
};

function expanded(key: string): boolean {
  return timeline.expanded.value.includes(key);
}

function toggle(key: string) {
  timeline.expanded.value = expanded(key)
    ? timeline.expanded.value.filter((value) => value !== key)
    : [...timeline.expanded.value, key];
}
</script>

<template>
  <div class="assistant-process-list">
    <div v-for="event in events" :key="event.key" class="assistant-process-step">
      <button
        type="button"
        class="assistant-process-step__toggle"
        :aria-expanded="expanded(event.key)"
        @click="toggle(event.key)"
      >
        <v-icon
          :icon="event.kind === 'THINKING' ? 'mdi-lightbulb-outline' : 'mdi-database-cog-outline'"
          size="14"
        />
        <span>{{ event.title }}</span>
        <v-icon
          :icon="expanded(event.key) ? 'mdi-chevron-up' : 'mdi-chevron-down'"
          size="14"
          class="ml-auto"
        />
      </button>
      <v-expand-transition>
        <div
          v-show="expanded(event.key)"
          v-auto-scroll="event.kind === 'THINKING' && expanded(event.key)"
          class="assistant-process-step__detail"
          :class="{
            'is-streaming': event.status.toUpperCase() === 'RUNNING',
          }"
          aria-live="polite"
        >
          {{ timeline.detail(event) }}
        </div>
      </v-expand-transition>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.assistant-process-list {
  margin: 2px 0 12px;
}

.assistant-process-step__toggle {
  display: flex;
  gap: 8px;
  align-items: center;
  width: 100%;
  min-height: 28px;
  padding: 5px 2px;
  font-size: 11.5px;
  line-height: 1.5;
  color: rgba(var(--v-theme-on-surface), 0.82);
  text-align: left;
  background: transparent;
  border: 0;
  cursor: pointer;
}

.assistant-process-step__detail {
  max-height: 112px;
  margin: 2px 24px 8px;
  padding: 8px 10px;
  overflow: auto;
  font-size: 11.5px;
  line-height: 1.5;
  color: rgba(var(--v-theme-on-surface), 0.68);
  white-space: pre-wrap;
  background: rgba(var(--v-theme-on-surface), 0.035);

  &.is-streaming {
    box-shadow: inset 2px 0 rgb(var(--v-theme-primary));
  }
}
</style>
