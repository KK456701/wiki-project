<script setup lang="ts">
export type StandardWorkspaceStep = 'selection' | 'checks' | 'data' | 'lineage'

defineProps<{
  currentStep: StandardWorkspaceStep
  hasCase: boolean
}>()

const emit = defineEmits<{
  navigate: [step: StandardWorkspaceStep]
}>()

const steps: Array<{ key: StandardWorkspaceStep; index: number; label: string; caption: string }> = [
  { key: 'selection', index: 1, label: '选择指标与口径', caption: '冻结本次排查范围' },
  { key: 'checks', index: 2, label: '基础检查', caption: '结构、事件与数据' },
  { key: 'data', index: 3, label: '数据确认', caption: '确认多算或少算' },
  { key: 'lineage', index: 4, label: '数据链路核查', caption: '定位并影子试跑' },
]
</script>

<template>
  <nav class="diagnosis-stepper" aria-label="标准异常排查步骤">
    <button v-for="item in steps" :key="item.key" type="button"
      :class="{ active: currentStep === item.key, available: item.key === 'selection' || hasCase }"
      :disabled="item.key !== 'selection' && !hasCase" @click="emit('navigate', item.key)">
      <b>{{ item.index }}</b><span><strong>{{ item.label }}</strong><small>{{ item.caption }}</small></span>
    </button>
  </nav>
</template>
