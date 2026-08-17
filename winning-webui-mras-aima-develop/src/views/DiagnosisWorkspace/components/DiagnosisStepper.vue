<script setup lang="ts">
import type { WorkspaceStep } from '../composables/useDiagnosisWorkspace';

const props = defineProps<{
  currentStep: WorkspaceStep;
  hasCase: boolean;
}>();

const emit = defineEmits<{ navigate: [step: WorkspaceStep] }>();

const steps: Array<{ key: WorkspaceStep; index: number; label: string; caption: string }> = [
  { key: 'selection', index: 1, label: '选择指标与口径', caption: '指标、口径与统计周期' },
  { key: 'data', index: 2, label: 'AI初步排查', caption: '查看指标详情与AI初步排查' },
  { key: 'lineage', index: 3, label: 'SQL脚本核查', caption: '定位问题数据链路' },
];

function stateOf(item: (typeof steps)[number]): 'done' | 'current' | 'pending' {
  const currentIndex = steps.find((s) => s.key === props.currentStep)?.index ?? 1;
  if (item.index < currentIndex) return 'done';
  if (item.index === currentIndex) return 'current';
  return 'pending';
}

function clickable(item: (typeof steps)[number]): boolean {
  return item.key === 'selection' || props.hasCase;
}
</script>

<template>
  <div class="dw-stepper d-flex align-center justify-center flex-wrap ga-1">
    <template v-for="(item, i) in steps" :key="item.key">
      <button
        type="button"
        class="dw-step"
        :class="[stateOf(item), { 'is-clickable': clickable(item) }]"
        :disabled="!clickable(item)"
        @click="clickable(item) && emit('navigate', item.key)"
      >
        <span class="dw-step-index">
          <v-icon v-if="stateOf(item) === 'done'" size="16" icon="mdi-check" />
          <template v-else>{{ item.index }}</template>
        </span>
        <span class="dw-step-text">
          <strong>{{ item.label }}</strong>
          <small>{{ item.caption }}</small>
        </span>
      </button>
      <v-icon
        v-if="i < steps.length - 1"
        class="dw-step-sep"
        :class="{ 'is-done': stateOf(item) === 'done' }"
        icon="mdi-chevron-right"
        size="20"
      />
    </template>
  </div>
</template>

<style lang="scss" scoped>
.dw-stepper {
  padding: 4px 8px;
}

.dw-step {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  padding: 6px 12px;
  border: 1px solid transparent;
  border-radius: 999px;
  background: transparent;
  cursor: default;
  transition:
    background 0.15s,
    border-color 0.15s;

  &.is-clickable {
    cursor: pointer;

    &:hover {
      background: rgba(var(--v-theme-primary), 0.06);
    }
  }

  &.current {
    background: rgba(var(--v-theme-primary), 0.1);
    border-color: rgb(var(--v-theme-primary));
  }

  &.done .dw-step-index {
    background: rgb(var(--v-theme-success));
    color: #fff;
  }
}

.dw-step-index {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border-radius: 50%;
  background: rgba(var(--v-theme-on-surface), 0.12);
  color: rgb(var(--v-theme-on-surface));
  font-weight: 600;
  font-size: 13px;
  flex-shrink: 0;
}

.dw-step.current .dw-step-index {
  background: rgb(var(--v-theme-primary));
  color: #fff;
}

.dw-step-text {
  display: flex;
  flex-direction: column;
  line-height: 1.25;
  text-align: left;

  strong {
    font-size: 13px;
    font-weight: 600;
  }

  small {
    font-size: 11px;
    color: rgba(var(--v-theme-on-surface), 0.6);
  }
}

.dw-step-sep {
  color: rgba(var(--v-theme-on-surface), 0.35);

  &.is-done {
    color: rgb(var(--v-theme-success));
  }
}
</style>
