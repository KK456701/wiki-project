<script setup lang="ts">
import { computed } from 'vue';
import type { GateResult, GateStatus } from '@/types/diagnosis';
import { GATE } from '@/constants/diagnosis';

const props = defineProps<{
  gateResults: GateResult[];
  gatesRunning: boolean;
  retry: () => void;
}>();

const PREPARATION = [
  {
    gate: GATE.SCHEMA,
    title: '数据结构校验',
    description: '核对业务库、真实库及当前口径所需表字段',
  },
  { gate: GATE.EVENT, title: '事件与抽取校验', description: '核对事件配置并重新计算当前口径' },
  { gate: GATE.VALUE, title: '数据可用性校验', description: '确认当前统计窗口存在可计算数据' },
] as const;

function gateResult(gate: number) {
  return props.gateResults.find((g) => Number(g.gate) === gate);
}

const activeGate = computed<number | null>(() => {
  for (const item of PREPARATION) {
    if (!gateResult(item.gate)) return item.gate;
  }
  return null;
});

function statusOf(gate: number): GateStatus {
  const r = gateResult(gate);
  if (r) return r.status as GateStatus;
  if (props.gatesRunning && activeGate.value === gate) return 'RUNNING' as GateStatus;
  return 'PENDING' as GateStatus;
}

const preparationSteps = computed(() =>
  PREPARATION.map((item) => {
    const r = gateResult(item.gate);
    return {
      ...item,
      status: statusOf(item.gate),
      message: r?.message ?? '',
      errorCode: r?.errorCode ?? '',
      repairSuggestion: r?.repairSuggestion ?? '',
    };
  }),
);

const allPassed = computed(() => PREPARATION.every((item) => statusOf(item.gate) === 'PASSED'));
const blockedGate = computed(() => preparationSteps.value.find((s) => s.status === 'BLOCKED'));
const showResult = computed(() => allPassed.value && !props.gatesRunning);
const showChecks = computed(() => !showResult.value);

function statusMeta(status: GateStatus): { label: string; color: string; icon: string } {
  switch (status) {
    case 'PASSED':
      return { label: '已通过', color: 'success', icon: 'mdi-check-circle' };
    case 'BLOCKED':
      return { label: '未通过', color: 'error', icon: 'mdi-close-circle' };
    case 'RUNNING':
      return { label: '校验中', color: 'primary', icon: '' };
    default:
      return { label: '等待中', color: 'default', icon: 'mdi-clock-outline' };
  }
}

defineExpose({ allPassed, showResult, showChecks, blockedGate, preparationSteps });
</script>

<template>
  <template v-if="showChecks">
    <v-alert
      v-if="blockedGate"
      type="error"
      variant="tonal"
      class="mb-3"
      :title="`${blockedGate.title}未通过`"
    >
      <div class="text-body-medium">
        {{ blockedGate.message || '后台校验发现需要处理的问题。' }}
      </div>
      <div v-if="blockedGate.errorCode" class="text-body-small mt-1">
        错误码：{{ blockedGate.errorCode }}
      </div>
      <div v-if="blockedGate.repairSuggestion" class="text-body-medium mt-2">
        <strong>建议处理：</strong>{{ blockedGate.repairSuggestion }}
      </div>
      <v-btn
        class="mt-3"
        size="small"
        variant="tonal"
        color="error"
        prepend-icon="mdi-refresh"
        :loading="gatesRunning"
        :disabled="gatesRunning"
        @click="retry"
      >
        修复后重新准备
      </v-btn>
    </v-alert>

    <v-card
      v-for="item in preparationSteps"
      :key="item.gate"
      variant="outlined"
      class="pa-3 mb-2"
      :class="{ 'dw-gate-running': item.status === 'RUNNING' }"
    >
      <div class="d-flex align-center ga-3">
        <v-progress-circular
          v-if="item.status === 'RUNNING'"
          indeterminate
          size="20"
          width="3"
          color="primary"
        />
        <v-icon
          v-else
          :icon="statusMeta(item.status).icon"
          :color="statusMeta(item.status).color"
          size="20"
        />
        <div>
          <div class="text-body-medium font-weight-medium">{{ item.title }}</div>
          <div class="text-body-small text-medium-emphasis">{{ item.description }}</div>
        </div>
        <v-spacer />
        <v-chip size="x-small" label :color="statusMeta(item.status).color" variant="tonal">
          {{ statusMeta(item.status).label }}
        </v-chip>
      </div>
      <div v-if="item.status === 'BLOCKED' && item.message" class="text-body-small text-error mt-2">
        {{ item.message }}
      </div>
    </v-card>
  </template>
</template>

<style lang="scss" scoped>
.dw-gate-running {
  border-color: rgb(var(--v-theme-primary));
}
</style>
