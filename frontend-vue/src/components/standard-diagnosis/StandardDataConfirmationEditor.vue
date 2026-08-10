<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  selectedCount: number
  overNote: string
  underNote: string
  busy?: boolean
}>()

const emit = defineEmits<{
  'update:overNote': [value: string]
  'update:underNote': [value: string]
  clearSelection: []
  submit: [options: { noIssue: boolean; openLineage: boolean }]
}>()

const hasIssue = computed(() => props.selectedCount > 0
  || props.overNote.trim().length > 0
  || props.underNote.trim().length > 0)
</script>

<template>
  <section>
    <header><strong>数据多了</strong><span>已选 {{ selectedCount }} 条</span></header>
    <p>勾选不应进入当前分子或分母的真实明细，也可以补充一类需要排除的数据。</p>
    <button v-if="selectedCount > 0" type="button" class="clear-selection" @click="emit('clearSelection')">清空已选记录</button>
    <textarea :value="overNote" rows="3" placeholder="例如：排除名称含“测试”的患者；排除某个科室。" @input="emit('update:overNote', ($event.target as HTMLTextAreaElement).value)" />
  </section>
  <section>
    <header><strong>数据少了</strong><span>描述预期范围</span></header>
    <p>没有患者编号也可以，写清楚缺少的科室、时间或业务数据。</p>
    <textarea :value="underNote" rows="3" placeholder="例如：骨伤一科在本统计期有手术患者，但分母明细没有该科室。" @input="emit('update:underNote', ($event.target as HTMLTextAreaElement).value)" />
  </section>
  <div class="confirmation-actions">
    <button type="button" class="workspace-primary" :disabled="!hasIssue || busy" @click="emit('submit', { noIssue: false, openLineage: false })">保存并查看澄清</button>
    <button type="button" class="workspace-secondary" :disabled="!hasIssue || busy" @click="emit('submit', { noIssue: false, openLineage: true })">保存并直接进入链路核查</button>
    <button type="button" class="workspace-secondary" :disabled="busy" @click="emit('submit', { noIssue: true, openLineage: false })">确认无异议</button>
  </div>
</template>
