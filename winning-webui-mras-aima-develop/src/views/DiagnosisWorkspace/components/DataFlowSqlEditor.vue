<script setup lang="ts">
import type { DataFlowNode } from '@/types/chat';
import { DATA_FLOW_NODE_TYPE } from '@/types/chat';
import type { AiScopeTarget } from '@/types/diagnosis';
import { computed, ref, watch } from 'vue';
import type { SqlGeneratePayload } from '@/views/DiagnosisWorkspace/composables/useDataFlowActions';
import { useAiSqlContext } from '@/views/DiagnosisWorkspace/composables/useAiSqlContext';
import AiExcludeScopePicker from './AiExcludeScopePicker.vue';
import UploadedSqlEditor from './UploadedSqlEditor.vue';

const EDIT_MODE = {
  DIRECT: 'direct',
  AI: 'ai',
} as const;

type EditMode = (typeof EDIT_MODE)[keyof typeof EDIT_MODE];
interface SqlRepairEntry {
  mode: 'AI_GENERATE_SQL' | 'FILTER_SQL' | 'FULL_CANDIDATE_SQL';
  membership?: 'INCLUDE' | 'EXCLUDE';
  sqlText?: string;
  requirement?: string;
  scopeTargets?: AiScopeTarget[];
  recommendedLayer?: 'SOURCE_EXTRACT' | 'OVERVIEW';
}

const props = defineProps<{
  node: DataFlowNode;
  generating: boolean;
  generateResult: string;
  generateError: string;
  /** 当前案例 ID（排查工作区上下文），为空时 AI 模式不可用 */
  caseId: string | null;
}>();

const emit = defineEmits<{
  (e: 'generate', payload: SqlGeneratePayload): void;
}>();

const editMode = ref<EditMode>(EDIT_MODE.DIRECT);
const directSql = ref('');
const aiRequirement = ref('');
const aiScopeTargets = ref<AiScopeTarget[]>([]);
const directSqlBaseline = computed(() => props.node.templateSql || props.node.sql || '');
const isSourceExtract = computed(
  () => props.node.nodeType === DATA_FLOW_NODE_TYPE.SOURCE_EXTRACT_SQL,
);

const { initialRequirement } = useAiSqlContext(() => props.caseId);

const aiAvailable = computed(
  () =>
    props.caseId !== null &&
    (props.node.nodeType === DATA_FLOW_NODE_TYPE.SOURCE_EXTRACT_SQL ||
      props.node.nodeType === DATA_FLOW_NODE_TYPE.OVERVIEW_SQL),
);
const repairEntry = computed<SqlRepairEntry | null>(() => {
  if (!props.caseId) return null;
  try {
    const raw = sessionStorage.getItem(`diagnosis-sql-repair:${props.caseId}`);
    return raw ? (JSON.parse(raw) as SqlRepairEntry) : null;
  } catch {
    return null;
  }
});

/**
 * 首次进入第 3 步（首个可编辑 SQL 节点）时，若带入了数据确认内容
 * （已选患者 / 科室或说明文字），默认切到「AI 生成对应 SQL」；
 * 此后切换 SQL 节点沿用既有直编默认，避免反复覆盖用户手动选择。
 */
let firstEntry = true;

watch(
  () => props.node.id,
  () => {
    directSql.value = directSqlBaseline.value;
    // 进入第 3 步或切换 SQL 节点时，把第 2 步数据确认固化的「数据多了/数据少了」
    // 内容自动带入 AI 生成 SQL 的输入框（对齐 readonly clarificationRequirement）。
    aiRequirement.value =
      firstEntry && repairEntry.value?.mode === 'AI_GENERATE_SQL'
        ? repairEntry.value.requirement ?? ''
        : isSourceExtract.value
          ? initialRequirement.value
          : '';
    aiScopeTargets.value =
      firstEntry && repairEntry.value?.mode === 'AI_GENERATE_SQL'
        ? repairEntry.value.scopeTargets ?? []
        : [];
    editMode.value =
      firstEntry && repairEntry.value?.mode === 'AI_GENERATE_SQL'
        ? EDIT_MODE.AI
        : firstEntry &&
            isSourceExtract.value &&
            aiAvailable.value &&
            initialRequirement.value.trim()
          ? EDIT_MODE.AI
          : EDIT_MODE.DIRECT;
    firstEntry = false;
  },
  { immediate: true },
);

/** 直编 SQL 是否相对当前正式 SQL 发生了变化 */
const directSqlChanged = computed(() => directSql.value.trim() !== directSqlBaseline.value.trim());

const canGenerate = computed(() => {
  if (editMode.value === EDIT_MODE.DIRECT) return directSqlChanged.value;
  const req = aiRequirement.value.trim();
  const hasScope = aiScopeTargets.value.length > 0;
  return req !== '' || hasScope;
});

function handleGenerate() {
  if (!canGenerate.value) return;
  emit('generate', {
    mode: editMode.value,
    requirement: editMode.value === EDIT_MODE.AI ? aiRequirement.value.trim() : '',
    candidateSql: editMode.value === EDIT_MODE.DIRECT ? directSql.value.trim() : '',
    scopeTargets: editMode.value === EDIT_MODE.AI ? aiScopeTargets.value : [],
  });
}

function updateScopeTargets(targets: AiScopeTarget[]) {
  aiScopeTargets.value = targets;
}

const uploadEntry = computed<
  (SqlRepairEntry & { mode: 'FILTER_SQL' | 'FULL_CANDIDATE_SQL' }) | null
>(() => {
  const entry = repairEntry.value;
  if (!entry || entry.mode === 'AI_GENERATE_SQL') return null;
  return entry as SqlRepairEntry & { mode: 'FILTER_SQL' | 'FULL_CANDIDATE_SQL' };
});
</script>

<template>
  <div class="sql-editor-section">
    <v-tabs v-model="editMode" density="comfortable" class="mb-2">
      <v-tab :value="EDIT_MODE.DIRECT" prepend-icon="mdi-pencil">手动编辑 SQL</v-tab>
      <v-tab :value="EDIT_MODE.AI" :disabled="!aiAvailable" prepend-icon="mdi-robot">
        AI 生成对应 SQL
      </v-tab>
    </v-tabs>

    <v-tabs-window v-model="editMode">
      <!-- 直接编辑模式 -->
      <v-tabs-window-item :value="EDIT_MODE.DIRECT">
        <UploadedSqlEditor
          v-if="uploadEntry && caseId"
          :case-id="caseId"
          :mode="uploadEntry.mode"
          :membership="uploadEntry.membership"
          :initial-sql="uploadEntry.sqlText ?? ''"
          :current-node-id="node.id"
        />
        <template v-else>
          <textarea v-model="directSql" class="sql-edit-textarea" rows="20" spellcheck="false" />
          <v-btn
            variant="tonal"
            color="primary"
            size="small"
            :loading="generating"
            :disabled="generating || !canGenerate"
            prepend-icon="mdi-content-save-outline"
            class="mt-2"
            @click="handleGenerate"
          >
            {{ generating ? '正在保存...' : '保存 SQL 修改' }}
          </v-btn>
          <div v-if="generateResult" class="text-body-small text-success mt-1">
            {{ generateResult }}
          </div>
          <div v-if="generateError" class="text-body-small text-error mt-1">
            {{ generateError }}
          </div>
        </template>
      </v-tabs-window-item>

      <!-- AI 生成模式 -->
      <v-tabs-window-item :value="EDIT_MODE.AI">
        <v-alert
          v-if="repairEntry?.mode === 'AI_GENERATE_SQL' && repairEntry.requirement"
          color="primary"
          variant="tonal"
          density="compact"
          class="mb-2 text-body-small"
        >
          已从 AI 初步排查带入：{{ repairEntry.requirement }}
        </v-alert>
        <textarea
          v-model="aiRequirement"
          class="sql-edit-textarea"
          rows="6"
          :placeholder="
            isSourceExtract
              ? '写清楚需要纳入或排除什么数据，以及使用哪个已有字段判断。已完成的数据确认内容会自动带入；也可以直接在这里填写。'
              : '描述概览结果的计算或汇总规则；概览 SQL 不会自动带入患者、科室排除条件。'
          "
        />
        <AiExcludeScopePicker
          v-if="isSourceExtract && repairEntry?.mode !== 'AI_GENERATE_SQL'"
          :case-id="caseId"
          @update:targets="updateScopeTargets"
        />
        <v-btn
          variant="tonal"
          color="primary"
          size="small"
          :loading="generating"
          :disabled="generating || !canGenerate"
          prepend-icon="mdi-auto-fix"
          class="mt-2"
          @click="handleGenerate"
        >
          {{ generating ? '正在生成 SQL...' : 'AI 生成 SQL' }}
        </v-btn>
        <div v-if="generateResult" class="text-body-small text-success mt-1">
          {{ generateResult }}
        </div>
        <div v-if="generateError" class="text-body-small text-error mt-1">
          {{ generateError }}
        </div>
      </v-tabs-window-item>
    </v-tabs-window>
  </div>
</template>

<style lang="scss" scoped>
.sql-editor-section {
  margin-top: 4px;
}

.sql-edit-textarea {
  width: 100%;
  padding: 8px 10px;
  border: 1px solid rgba(var(--v-border-color), 0.2);
  border-radius: 4px;
  background: rgba(var(--v-theme-on-surface), 0.03);
  color: rgba(var(--v-theme-on-surface), 0.87);
  font-family: 'Roboto Mono', 'Courier New', monospace;
  font-size: 11px;
  line-height: 1.5;
  resize: vertical;
  outline: none;

  &:focus {
    border-color: rgba(var(--v-theme-primary), 0.5);
  }

  &::placeholder {
    font-family: inherit;
    font-size: 11px;
    color: rgba(var(--v-theme-on-surface), 0.42);
  }
}
</style>
