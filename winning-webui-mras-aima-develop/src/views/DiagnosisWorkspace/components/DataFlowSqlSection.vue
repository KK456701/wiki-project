<script setup lang="ts">
import type { DataFlowNode } from '@/types/chat';
import type { DiagnosisCaseSnapshot } from '@/types/diagnosis';
import { computed, ref } from 'vue';
import { useClipboard } from '@vueuse/core';
import type { SqlGeneratePayload } from '@/views/DiagnosisWorkspace/composables/useDataFlowActions';
import { dataFlowNodeLayer } from '@/views/DiagnosisWorkspace/composables/useDataFlowNodeInfo';
import DiagnosisSqlExecuteButton from './DiagnosisSqlExecuteButton.vue';
import DataFlowSqlEditor from './DataFlowSqlEditor.vue';
import AiSqlCandidatePanel from './AiSqlCandidatePanel.vue';

const props = defineProps<{
  node: DataFlowNode;
  generating: boolean;
  generateResult: string;
  generateError: string;
  generationLayer: string;
  caseId: string | null;
  generationSnapshot: DiagnosisCaseSnapshot | null;
}>();

const emit = defineEmits<{
  (e: 'generate', payload: SqlGeneratePayload): void;
}>();

const hasSql = computed(() => props.node.sqlKind !== '' && props.node.sql !== '');

/** 原 SQL 脚本默认收起，用户可手动展开 */
const isSqlExpanded = ref(false);

const layer = computed(() => dataFlowNodeLayer(props.node));

/** 可编辑节点：源表抽取 / 概览 SQL（支持重新执行与 SQL 修改） */
const isEditable = computed(() => layer.value !== '');
const showsCurrentGeneration = computed(
  () => !props.generationLayer || props.generationLayer === layer.value,
);

/** 节点区域只展示候选 SQL 本身；整体执行结果统一由外层结果弹窗呈现。 */
const candidatePreviewSnapshot = computed(() =>
  props.generationSnapshot &&
  (!props.generationSnapshot.candidateSql?.layer ||
    String(props.generationSnapshot.candidateSql.layer) === layer.value ||
    (Array.isArray(props.generationSnapshot.candidateSql.changes) &&
      props.generationSnapshot.candidateSql.changes.some(
        (value) => record(value).nodeId === props.node.id,
      )))
    ? { ...props.generationSnapshot, shadowTrial: {} }
    : null,
);

function record(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' ? (value as Record<string, unknown>) : {};
}

/** SQL 按行拆分，用于带行号展示 */
const sqlLines = computed(() => props.node.sql.split('\n'));

const { copy: clipboardCopy, copied: clipboardCopied } = useClipboard({ legacy: true });

async function copySql() {
  if (props.node.sql) {
    await clipboardCopy(props.node.sql);
  }
}
</script>

<template>
  <v-card v-if="hasSql" variant="outlined" rounded="lg">
    <div class="sql-toolbar d-flex align-center ga-2 px-4 pt-3 pb-1">
      <v-icon icon="mdi-database-search-outline" size="small" color="primary" />
      <span class="text-label-large">原 SQL 脚本</span>
      <v-btn
        variant="text"
        size="x-small"
        class="text-label-large"
        :append-icon="isSqlExpanded ? 'mdi-chevron-up' : 'mdi-chevron-down'"
        @click="isSqlExpanded = !isSqlExpanded"
      >
        {{ isSqlExpanded ? '收起' : '展开' }}
      </v-btn>
      <v-spacer />
      <DiagnosisSqlExecuteButton
        :sql="node.sql"
        :role-hint="node.databaseRole || node.nodeType"
        :node-kind="node.nodeType"
        :snapshot="generationSnapshot"
      />
      <v-btn
        variant="text"
        size="x-small"
        class="text-label-large"
        :color="clipboardCopied ? 'success' : undefined"
        :prepend-icon="clipboardCopied ? 'mdi-check-circle-outline' : 'mdi-content-copy'"
        @click="copySql"
      >
        {{ clipboardCopied ? '已复制' : '复制 SQL' }}
      </v-btn>
    </div>

    <v-expand-transition>
      <v-card-text v-show="isSqlExpanded">
        <div class="sql-block">
          <div v-for="(line, idx) in sqlLines" :key="idx" class="sql-line">
            <span class="sql-line-no">{{ idx + 1 }}</span>
            <code class="sql-line-code">{{ line }}</code>
          </div>
        </div>
      </v-card-text>
    </v-expand-transition>

    <template v-if="isEditable">
      <v-divider class="mx-4" />
      <v-card-text>
        <DataFlowSqlEditor
          :node="node"
          :generating="showsCurrentGeneration && generating"
          :generate-result="showsCurrentGeneration ? generateResult : ''"
          :generate-error="showsCurrentGeneration ? generateError : ''"
          :case-id="caseId"
          @generate="emit('generate', $event)"
        />
        <AiSqlCandidatePanel :snapshot="candidatePreviewSnapshot" :node-id="node.id" />
      </v-card-text>
    </template>
  </v-card>
</template>

<style lang="scss" scoped>
.sql-block {
  background: #1e1e1e;
  border-radius: 6px;
  padding: 8px 0;
  max-height: 320px;
  overflow-y: auto;
}

.sql-line {
  display: flex;
  align-items: flex-start;
  padding: 0 12px;
  line-height: 1.5;
  font-family: 'Roboto Mono', 'Courier New', monospace;
  font-size: 11px;

  &:hover {
    background: rgba(255, 255, 255, 0.04);
  }
}

.sql-line-no {
  flex-shrink: 0;
  width: 28px;
  margin-right: 8px;
  text-align: right;
  color: #6e7681;
  user-select: none;
}

.sql-line-code {
  white-space: pre-wrap;
  word-break: break-all;
  color: #c9d1d9;
}
</style>
