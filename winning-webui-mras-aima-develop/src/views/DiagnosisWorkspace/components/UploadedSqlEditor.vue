<script setup lang="ts">
import { computed, ref } from 'vue';
import { analyzeUploadedSql, createCandidateChangeSet } from '@/services/diagnosis';
import { useDiagnosisStore } from '@/stores/diagnosis';
import type {
  UploadedSqlAnalysis,
  UploadedSqlMembership,
  UploadedSqlMode,
  UploadedSqlRequest,
} from '@/types/diagnosis';

const props = defineProps<{
  caseId: string;
  mode: UploadedSqlMode;
  membership?: UploadedSqlMembership;
  currentNodeId: string;
  initialSql?: string;
}>();

const emit = defineEmits<{ created: [] }>();
const diagnosis = useDiagnosisStore();
const sqlText = ref(props.initialSql ?? '');
const analysis = ref<UploadedSqlAnalysis>();
const targetNodeId = ref('');
const dependencyConfirmed = ref(false);
const busy = ref(false);
const stage = ref('');
const error = ref('');
const detailsOpen = ref(false);

const tone = computed(() => {
  if (props.mode === 'FULL_CANDIDATE_SQL') return 'default';
  return props.membership === 'EXCLUDE' ? 'warning' : 'primary';
});
const modeLabel = computed(() => {
  if (props.mode === 'FULL_CANDIDATE_SQL') return '上传完整候选 SQL';
  return props.membership === 'EXCLUDE'
    ? '上传排查患者或科室的 SQL'
    : '上传新增患者或科室的 SQL';
});
const canCreate = computed(() => {
  const impact = analysis.value?.impactAnalysis;
  if (!impact || busy.value) return false;
  if (impact.requiresDependencyConfirmation && !dependencyConfirmed.value) return false;
  return !impact.ambiguous || Boolean(targetNodeId.value);
});

function requestPayload(): UploadedSqlRequest {
  return {
    mode: props.mode,
    membership: props.membership,
    sqlText: sqlText.value.trim(),
    targetNodeId: targetNodeId.value || undefined,
    confirmNewDependencies: dependencyConfirmed.value,
  };
}

async function analyze() {
  if (!sqlText.value.trim()) return;
  busy.value = true;
  error.value = '';
  stage.value = '正在校验只读 SQL…';
  try {
    stage.value = '正在识别数据库、表和匹配键…';
    analysis.value = await analyzeUploadedSql(props.caseId, requestPayload());
    targetNodeId.value =
      analysis.value.targetChoices.length === 1
        ? (analysis.value.targetChoices[0]?.nodeId ?? '')
        : '';
    dependencyConfirmed.value = false;
    stage.value = '分析完成';
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '上传 SQL 分析失败';
    stage.value = '';
  } finally {
    busy.value = false;
  }
}

async function createChangeSet() {
  if (!canCreate.value) return;
  busy.value = true;
  error.value = '';
  stage.value = '正在确定性生成候选 SQL…';
  try {
    await createCandidateChangeSet(props.caseId, requestPayload());
    stage.value = '正在整理多节点变更集…';
    await diagnosis.loadCase(props.caseId);
    stage.value = '候选 SQL 已生成';
    sessionStorage.removeItem(`diagnosis-sql-repair:${props.caseId}`);
    emit('created');
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '生成候选 SQL 失败';
    stage.value = '';
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <section class="uploaded-sql-editor">
    <div class="d-flex align-center ga-2 mb-2">
      <v-chip :color="tone" variant="tonal" size="small">{{ modeLabel }}</v-chip>
      <span class="text-body-small text-medium-emphasis">已从 AI 初步排查带入，可继续核对</span>
    </div>
    <v-textarea
      v-model="sqlText"
      label="已上传 SQL"
      variant="outlined"
      rows="9"
      class="uploaded-sql-editor__input"
      spellcheck="false"
      hide-details="auto"
      @update:model-value="analysis = undefined"
    />
    <div class="d-flex align-center ga-2 mt-2">
      <v-btn
        color="primary"
        variant="tonal"
        size="small"
        :loading="busy && !analysis"
        :disabled="busy || !sqlText.trim()"
        prepend-icon="mdi-text-search"
        @click="analyze"
      >
        分析 SQL
      </v-btn>
      <span v-if="stage" class="text-body-small text-medium-emphasis">{{ stage }}</span>
    </div>
    <v-alert v-if="error" type="error" variant="tonal" density="compact" class="mt-2">
      {{ error }}
    </v-alert>

    <template v-if="analysis">
      <v-alert :color="tone" variant="tonal" density="compact" class="mt-3">
        {{ analysis.summary }}
      </v-alert>
      <v-select
        v-if="analysis.impactAnalysis.ambiguous"
        v-model="targetNodeId"
        :items="analysis.targetChoices"
        item-title="label"
        item-value="nodeId"
        label="选择需要替换的 SQL 节点"
        variant="outlined"
        density="compact"
        class="mt-3"
        hide-details="auto"
      />
      <v-checkbox
        v-if="analysis.impactAnalysis.requiresDependencyConfirmation"
        v-model="dependencyConfirmed"
        color="primary"
        density="compact"
        hide-details
        class="mt-2"
        label="我已确认新增业务表属于当前指标业务口径"
      />
      <v-btn
        variant="text"
        size="small"
        :append-icon="detailsOpen ? 'mdi-chevron-up' : 'mdi-chevron-down'"
        @click="detailsOpen = !detailsOpen"
      >
        查看分析详情
      </v-btn>
      <v-expand-transition>
        <v-list v-show="detailsOpen" density="compact" class="uploaded-sql-editor__details">
          <v-list-item
            title="数据库 / 方言"
            :subtitle="`${analysis.impactAnalysis.database} / ${analysis.impactAnalysis.dialect}`"
          />
          <v-list-item
            title="引用表"
            :subtitle="analysis.impactAnalysis.referencedTables.join('、')"
          />
          <v-list-item
            title="输出字段"
            :subtitle="analysis.impactAnalysis.outputFields.join('、') || '未识别别名字段'"
          />
          <v-list-item title="稳定匹配键" :subtitle="analysis.impactAnalysis.matchKey || '无'" />
          <v-list-item title="推荐修改层" :subtitle="analysis.impactAnalysis.recommendedLayer" />
          <v-list-item
            title="受影响节点"
            :subtitle="analysis.impactAnalysis.affectedNodeIds.join('、')"
          />
          <v-list-item
            title="新增依赖"
            :subtitle="analysis.impactAnalysis.newDependencies.join('、') || '无'"
          />
        </v-list>
      </v-expand-transition>
      <v-btn
        color="primary"
        variant="flat"
        size="small"
        class="mt-2"
        :loading="busy"
        :disabled="!canCreate"
        prepend-icon="mdi-auto-fix"
        @click="createChangeSet"
      >
        生成候选 SQL
      </v-btn>
    </template>
  </section>
</template>

<style scoped>
.uploaded-sql-editor__input :deep(textarea) {
  font-family: 'Roboto Mono', 'Courier New', monospace;
  font-size: 11px;
  line-height: 1.5;
}

.uploaded-sql-editor__details {
  border: 1px solid rgba(var(--v-theme-outline), 0.2);
  border-radius: 8px;
}
</style>
