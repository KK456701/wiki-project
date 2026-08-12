<script setup lang="ts">
export type StandardWorkspaceStep = 'selection' | 'data' | 'lineage'

const props = defineProps<{
  currentStep: StandardWorkspaceStep
  hasCase: boolean
}>()

const emit = defineEmits<{
  navigate: [step: StandardWorkspaceStep]
}>()

const steps: Array<{ key: StandardWorkspaceStep; index: number; label: string; caption: string }> = [
  { key: 'selection', index: 1, label: '选择指标与口径', caption: '已选择指标和口径范围' },
  { key: 'data', index: 2, label: '数据确认', caption: '核对指标结果与异常数据' },
  { key: 'lineage', index: 3, label: '数据链路核查', caption: '定位问题并完成影子试跑' },
]

function stateFor(item: (typeof steps)[number]) {
  const currentIndex = steps.find((step) => step.key === props.currentStep)?.index || 1
  return item.index < currentIndex ? 'done' : item.index === currentIndex ? 'current' : 'pending'
}

</script>

<template>
  <nav class="diagnosis-stepper" aria-label="标准异常排查步骤">
    <button v-for="item in steps" :key="item.key" type="button"
      :class="[stateFor(item), { active: currentStep === item.key, available: item.key === 'selection' || hasCase }]"
      :data-state="stateFor(item)"
      :disabled="item.key !== 'selection' && !hasCase" @click="emit('navigate', item.key)">
      <b>{{ stateFor(item) === 'done' ? '✓' : item.index }}</b>
      <span><strong>{{ item.label }}</strong><small><em>{{ stateFor(item) === 'done' ? '已完成' : stateFor(item) === 'current' ? '进行中' : '待开始' }}</em><i aria-hidden="true">·</i>{{ stateFor(item) === 'current' ? '当前步骤' : item.caption }}</small></span>
      <i aria-hidden="true">›</i>
    </button>
  </nav>
</template>
