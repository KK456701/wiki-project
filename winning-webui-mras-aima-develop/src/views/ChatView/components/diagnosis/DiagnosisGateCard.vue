<script setup lang="ts">
import { computed } from 'vue';
import type { GateResult } from '@/types/diagnosis';

const props = defineProps<{
  gateResult: GateResult;
}>();

const emit = defineEmits<{ recheck: [gate: number] }>();

const GATE_LABELS: Record<number, string> = {
  1: '第 1 关 · 数据结构校验',
  2: '第 2 关 · 事件配置校验',
  3: '第 3 关 · 现场数值校验',
};

const title = computed(
  () => GATE_LABELS[props.gateResult.gate] ?? props.gateResult.name ?? '关卡校验',
);

const statusColor = computed(() => {
  switch (props.gateResult.status) {
    case 'PASSED':
      return 'success';
    case 'BLOCKED':
      return 'error';
    default:
      return 'grey';
  }
});

const statusText = computed(() => {
  switch (props.gateResult.status) {
    case 'PASSED':
      return '通过';
    case 'BLOCKED':
      return '未通过';
    default:
      return '待执行';
  }
});

const isBlocked = computed(() => props.gateResult.status === 'BLOCKED');
</script>

<template>
  <v-card variant="outlined" class="gate-card" :class="{ 'gate-card--blocked': isBlocked }">
    <v-card-text class="pa-3">
      <div class="d-flex align-center justify-space-between mb-1">
        <div class="text-label-large font-weight-medium d-flex align-center ga-1">
          <v-icon icon="mdi-shield-check-outline" size="18" color="primary" />
          {{ title }}
        </div>
        <v-chip :color="statusColor" size="x-small" label variant="flat">
          {{ statusText }}
        </v-chip>
      </div>

      <p v-if="gateResult.message" class="text-body-medium text-medium-emphasis mb-0">
        {{ gateResult.message }}
      </p>

      <div v-if="isBlocked && gateResult.repairSuggestion" class="repair-suggestion mt-2 pa-2">
        <div class="text-body-small font-weight-medium text-error mb-1">
          <v-icon icon="mdi-wrench-outline" size="14" start />
          修复建议
        </div>
        <p class="text-body-medium mb-0">{{ gateResult.repairSuggestion }}</p>
      </div>

      <div v-if="isBlocked" class="d-flex justify-end mt-2">
        <v-btn
          size="small"
          variant="tonal"
          color="error"
          prepend-icon="mdi-refresh"
          @click="emit('recheck', gateResult.gate)"
        >
          修复后重新检查
        </v-btn>
      </div>
    </v-card-text>
  </v-card>
</template>

<style lang="scss" scoped>
.gate-card {
  border-left: 3px solid rgb(var(--v-theme-success));

  &--blocked {
    border-left-color: rgb(var(--v-theme-error));
  }
}

.repair-suggestion {
  background: rgba(var(--v-theme-error), 0.06);
  border-radius: 6px;
}
</style>
