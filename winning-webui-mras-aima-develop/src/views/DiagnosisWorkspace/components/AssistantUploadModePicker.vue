<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { getSqlRepairOptions } from '@/services/diagnosis';
import type { SqlUploadExample } from '@/types/diagnosis';

export interface UploadSqlEntry {
  mode: 'FILTER_SQL' | 'FULL_CANDIDATE_SQL';
  membership?: 'INCLUDE' | 'EXCLUDE';
  sqlText: string;
}

const emit = defineEmits<{
  select: [value: UploadSqlEntry];
  close: [];
}>();
const props = defineProps<{ caseId: string }>();

const selected = ref<Omit<UploadSqlEntry, 'sqlText'>>();
const sqlText = ref('');
const examples = ref<SqlUploadExample[]>([]);
const expandedExample = ref('');
const canConfirm = computed(() => Boolean(selected.value && sqlText.value.trim()));
const entries: Array<{
  value: Omit<UploadSqlEntry, 'sqlText'>;
  label: string;
  icon: string;
}> = [
  {
    value: { mode: 'FILTER_SQL', membership: 'EXCLUDE' },
    label: '上传排查患者或科室的 SQL',
    icon: 'mdi-account-search-outline',
  },
  {
    value: { mode: 'FILTER_SQL', membership: 'INCLUDE' },
    label: '上传新增患者或科室的 SQL',
    icon: 'mdi-account-plus-outline',
  },
  {
    value: { mode: 'FULL_CANDIDATE_SQL' },
    label: '上传完整候选 SQL',
    icon: 'mdi-file-code-outline',
  },
];

function entryKey(value: Omit<UploadSqlEntry, 'sqlText'>) {
  return `${value.mode}:${value.membership ?? ''}`;
}

function isSelected(value: Omit<UploadSqlEntry, 'sqlText'>) {
  return selected.value != null && entryKey(selected.value) === entryKey(value);
}

function exampleFor(value: Omit<UploadSqlEntry, 'sqlText'>) {
  return examples.value.find(
    (item) => item.mode === value.mode && (item.membership ?? '') === (value.membership ?? ''),
  );
}

function toggleExample(value: Omit<UploadSqlEntry, 'sqlText'>) {
  const key = entryKey(value);
  expandedExample.value = expandedExample.value === key ? '' : key;
}

function choose(value: Omit<UploadSqlEntry, 'sqlText'>) {
  selected.value = value;
}

function confirm() {
  if (!selected.value || !sqlText.value.trim()) return;
  const value: UploadSqlEntry = { ...selected.value, sqlText: sqlText.value.trim() };
  // 只有确认后才离开第二步，避免空模式或未完成 SQL 被带入第三步。
  emit('select', value);
}

onMounted(async () => {
  try {
    examples.value = (await getSqlRepairOptions(props.caseId)).uploadExamples ?? [];
  } catch {
    examples.value = [];
  }
});
</script>

<template>
  <v-card variant="outlined" class="upload-mode-picker pa-3" rounded="lg">
    <div class="d-flex align-center justify-space-between mb-2">
      <span class="text-title-small">选择上传方式</span>
      <v-btn
        icon="mdi-close"
        variant="text"
        size="x-small"
        aria-label="关闭上传方式"
        @click="$emit('close')"
      />
    </div>
    <div class="upload-mode-picker__items">
      <div v-for="entry in entries" :key="entryKey(entry.value)" class="upload-mode-picker__item">
        <v-btn
          :variant="isSelected(entry.value) ? 'flat' : 'outlined'"
          :color="isSelected(entry.value) ? 'primary' : undefined"
          class="upload-mode-picker__choice justify-start"
          :class="{ 'is-selected': isSelected(entry.value) }"
          :prepend-icon="entry.icon"
          @click="choose(entry.value)"
        >
          {{ entry.label }}
        </v-btn>
        <v-btn
          v-if="exampleFor(entry.value)"
          variant="text"
          size="x-small"
          class="upload-mode-picker__example-trigger"
          @click="toggleExample(entry.value)"
        >
          {{ expandedExample === entryKey(entry.value) ? '收起示例' : '查看示例' }}
        </v-btn>
        <v-expand-transition>
          <pre
            v-if="expandedExample === entryKey(entry.value) && exampleFor(entry.value)"
            class="upload-mode-picker__example"
          ><code>{{ exampleFor(entry.value)?.sqlText }}</code></pre>
        </v-expand-transition>
      </div>
    </div>
    <v-textarea
      v-model="sqlText"
      class="upload-mode-picker__sql mt-3"
      label="粘贴 SQL 文本"
      placeholder="请复制并粘贴筛选 SQL 或完整候选 SQL"
      variant="outlined"
      density="compact"
      rows="7"
      hide-details="auto"
      spellcheck="false"
    />
    <p class="text-body-small text-medium-emphasis mt-2 mb-0">
      仅支持粘贴 SQL 文本。确认后进入 SQL 脚本核查，并继续完成只读、安全和节点匹配校验。
    </p>
    <div class="d-flex justify-end mt-3">
      <v-btn
        color="primary"
        variant="flat"
        size="small"
        :disabled="!canConfirm"
        prepend-icon="mdi-arrow-right"
        @click="confirm"
      >
        确认并进入 SQL 脚本核查
      </v-btn>
    </div>
  </v-card>
</template>

<style scoped>
.upload-mode-picker {
  width: min(430px, calc(100vw - 48px));
  border-color: rgba(var(--v-theme-outline), 0.24);
  background: rgb(var(--v-theme-surface));
}

.upload-mode-picker__items {
  display: grid;
  gap: 8px;
}

.upload-mode-picker__item {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 4px;
}

.upload-mode-picker__choice:not(.is-selected) {
  color: rgb(var(--v-theme-on-surface)) !important;
  background: rgb(var(--v-theme-surface)) !important;
  border-color: rgba(var(--v-theme-outline), 0.28);
}

.upload-mode-picker__choice.is-selected {
  color: rgb(var(--v-theme-on-primary)) !important;
  background: rgb(var(--v-theme-primary)) !important;
}

.upload-mode-picker__choice.is-selected :deep(.v-btn__content),
.upload-mode-picker__choice.is-selected :deep(.v-icon) {
  color: rgb(var(--v-theme-on-primary)) !important;
}

:deep(.upload-mode-picker__choice.is-selected .v-btn__prepend),
:deep(.upload-mode-picker__choice.is-selected .v-icon) {
  color: rgb(var(--v-theme-on-primary)) !important;
}

.upload-mode-picker__example-trigger {
  color: rgb(var(--v-theme-on-surface-variant));
}

.upload-mode-picker__example {
  grid-column: 1 / -1;
  max-height: 150px;
  margin: 0;
  padding: 10px;
  overflow: auto;
  border: 1px solid rgba(var(--v-theme-outline), 0.18);
  border-radius: 6px;
  background: rgba(var(--v-theme-on-surface), 0.035);
  font-size: 11px;
  white-space: pre-wrap;
}

.upload-mode-picker__sql :deep(textarea) {
  font-family: 'Roboto Mono', 'Courier New', monospace;
  font-size: 11px;
  line-height: 1.5;
}
</style>
