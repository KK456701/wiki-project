<script setup lang="ts">
import { computed, ref } from 'vue'

import type { AgentClarification, AgentClarificationOption } from '../api/agent'

const props = defineProps<{
  clarification: AgentClarification
  disabled?: boolean
  resolved?: boolean
}>()

const emit = defineEmits<{
  submit: [values: string[]]
}>()

const search = ref('')
const selected = ref<string[]>([])
const freeText = ref('')

const filtered = computed(() => {
  const keyword = search.value.trim().toLowerCase()
  if (!keyword) return props.clarification.options
  return props.clarification.options.filter((option) =>
    `${option.label} ${option.value} ${option.description}`
      .toLowerCase()
      .includes(keyword),
  )
})

const groups = computed(() => {
  const values = new Map<string, AgentClarificationOption[]>()
  for (const option of filtered.value) {
    const group = option.group || '可选操作'
    const items = values.get(group) || []
    items.push(option)
    values.set(group, items)
  }
  return [...values.entries()]
})

function choose(option: AgentClarificationOption) {
  if (props.disabled || props.resolved) return
  if (props.clarification.selectionMode === 'single') {
    emit('submit', [option.value])
    return
  }
  if (option.id === 'indicator:all') {
    selected.value = selected.value.includes(option.id) ? [] : [option.id]
    return
  }
  const withoutAll = selected.value.filter((id) => id !== 'indicator:all')
  selected.value = withoutAll.includes(option.id)
    ? withoutAll.filter((id) => id !== option.id)
    : [...withoutAll, option.id]
}

function submitMultiple() {
  const values = props.clarification.options
    .filter((option) => selected.value.includes(option.id))
    .map((option) => option.value)
  if (values.length) emit('submit', values)
}

function submitFreeText() {
  const value = freeText.value.trim()
  if (value) emit('submit', [value])
}
</script>

<template>
  <section
    class="clarification-panel"
    :class="{ 'is-resolved': resolved }"
    aria-label="需要补充的信息"
  >
    <header>
      <span>{{ resolved ? '已收到选择' : '需要你确认' }}</span>
      <strong>{{ clarification.title }}</strong>
      <p>{{ clarification.question }}</p>
    </header>

    <p v-if="clarification.helpText" class="clarification-help">
      {{ clarification.helpText }}
    </p>

    <template v-if="!resolved">
      <label v-if="clarification.options.length > 8" class="clarification-search">
        <span>搜索可选项</span>
        <input v-model="search" type="search" placeholder="输入指标名称或关键词" />
      </label>

      <div v-if="clarification.options.length" class="clarification-options">
        <section v-for="[group, options] in groups" :key="group" class="clarification-group">
          <h4>{{ group }} <small>{{ options.length }}</small></h4>
          <div class="clarification-option-grid">
            <button
              v-for="option in options"
              :key="option.id"
              type="button"
              class="clarification-option"
              :class="{ 'is-selected': selected.includes(option.id) }"
              :aria-pressed="clarification.selectionMode === 'multiple'
                ? selected.includes(option.id)
                : undefined"
              :disabled="disabled"
              @click="choose(option)"
            >
              <strong>{{ option.label }}</strong>
              <span v-if="option.description">{{ option.description }}</span>
            </button>
          </div>
        </section>
        <p v-if="!filtered.length" class="clarification-empty">
          没有匹配项，可以在下方直接补充你的说法。
        </p>
      </div>

      <button
        v-if="clarification.selectionMode === 'multiple' && selected.length"
        type="button"
        class="clarification-confirm"
        :disabled="disabled"
        @click="submitMultiple"
      >
        用已选的 {{ selected.length }} 个指标继续
      </button>

      <form
        v-if="clarification.allowFreeText"
        class="clarification-free-text"
        @submit.prevent="submitFreeText"
      >
        <label>
          <span>{{ clarification.options.length ? '以上都不合适？' : '请补充说明' }}</span>
          <input
            v-model="freeText"
            :placeholder="clarification.freeTextPlaceholder"
            :disabled="disabled"
          />
        </label>
        <button type="submit" :disabled="disabled || !freeText.trim()">继续处理</button>
      </form>
    </template>
  </section>
</template>
