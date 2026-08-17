<script setup lang="ts">
import { computed } from 'vue';
import type { EvidenceItem } from '@/types/diagnosis';
import SqlExecuteButton from '@/components/SqlExecuteButton.vue';
import { inferSqlDatabaseRole } from '@/services/sql-preview';

const props = defineProps<{
  item: EvidenceItem;
  ruleId: string;
  profileId: string;
  statStart: string;
  statEnd: string;
}>();

const analysis = computed(() => props.item.requirementAnalysis ?? {});
const sqlContext = computed(() => props.item.sqlContext ?? {});
const display = computed(() => props.item.display ?? {});
const executableRole = computed(() =>
  inferSqlDatabaseRole(
    str(sqlContext.value['executableSql']),
    str(sqlContext.value['databaseRole'] ?? sqlContext.value['layer'] ?? props.item.suspectedLayer),
  ),
);
const candidateRole = computed(() =>
  inferSqlDatabaseRole(props.item.candidateSql ?? '', props.item.suspectedLayer),
);

function str(v: unknown): string {
  if (v === null || v === undefined) return '';
  return typeof v === 'string' ? v : JSON.stringify(v);
}

function stageRole(stage: NonNullable<EvidenceItem['stages']>[number]) {
  return inferSqlDatabaseRole(stage.sql ?? '', stage.databaseRole ?? stage.stage);
}
</script>

<template>
  <div class="evidence-item">
    <div class="text-body-medium font-weight-medium">{{ item.summary }}</div>
    <div class="d-flex flex-wrap ga-1 mt-1">
      <v-chip v-if="item.type" size="x-small" label variant="tonal">{{ item.type }}</v-chip>
      <v-chip v-if="item.suspectedLayer" size="x-small" label variant="tonal">{{
        item.suspectedLayer
      }}</v-chip>
      <span v-if="item.aiAnalysis" class="text-body-small text-medium-emphasis"
        >AI：{{ item.aiAnalysis }}</span
      >
    </div>

    <p v-if="item.requirement" class="text-body-small text-medium-emphasis mt-1 mb-0">
      要求：{{ item.requirement }}
    </p>

    <!-- 实施人员 SQL 要求：分析结果 + SQL 上下文 -->
    <template v-if="item.type === 'IMPLEMENTER_SQL_REQUIREMENT'">
      <div v-if="analysis.judgement || analysis.nextAction" class="analysis-box mt-2 pa-2">
        <div v-if="analysis.judgement" class="text-body-small">
          <span class="font-weight-medium">判定：</span>{{ str(analysis.judgement) }}
        </div>
        <div v-if="analysis.nextAction" class="text-body-small">
          <span class="font-weight-medium">下一步：</span>{{ str(analysis.nextAction) }}
        </div>
        <div v-if="analysis.sqlGeneration" class="text-body-small">
          <span class="font-weight-medium">SQL 生成：</span>{{ str(analysis.sqlGeneration) }}
        </div>
      </div>
      <div v-if="sqlContext.layerLabel" class="text-body-small text-medium-emphasis mt-1">
        上下文层：{{ str(sqlContext.layerLabel) }}
        <span v-if="sqlContext.available">（可用）</span>
        <span v-else>（暂不可用）</span>
      </div>
      <div class="pa-1">
        <v-expansion-panels v-if="sqlContext.executableSql" variant="accordion" density="compact">
          <v-expansion-panel>
            <v-expansion-panel-title class="text-body-small py-1">
              可执行 SQL
            </v-expansion-panel-title>
            <v-expansion-panel-text>
              <div class="d-flex justify-end mb-1">
                <SqlExecuteButton
                  :sql="str(sqlContext.executableSql)"
                  :database-role="executableRole"
                  :rule-id="ruleId"
                  :profile-id="profileId"
                  :stat-start="statStart"
                  :stat-end="statEnd"
                  size="x-small"
                />
              </div>
              <pre class="sql-pre">{{ str(sqlContext.executableSql) }}</pre>
            </v-expansion-panel-text>
          </v-expansion-panel>
        </v-expansion-panels>
      </div>
      <p v-if="sqlContext.currentResult" class="text-body-small text-medium-emphasis mb-0">
        当前结果：{{ str(sqlContext.currentResult) }}
      </p>
      <div class="pa-1">
        <v-expansion-panels
          v-if="item.candidateSql"
          variant="accordion"
          density="compact"
          class="my-1"
        >
          <v-expansion-panel>
            <v-expansion-panel-title class="text-body-small py-1">
              候选 SQL
            </v-expansion-panel-title>
            <v-expansion-panel-text>
              <div class="d-flex justify-end mb-1">
                <SqlExecuteButton
                  :sql="item.candidateSql"
                  :database-role="candidateRole"
                  :rule-id="ruleId"
                  :profile-id="profileId"
                  :stat-start="statStart"
                  :stat-end="statEnd"
                  size="x-small"
                />
              </div>
              <pre class="sql-pre">{{ item.candidateSql }}</pre>
            </v-expansion-panel-text>
          </v-expansion-panel>
        </v-expansion-panels>
      </div>
    </template>

    <!-- 自动取证回流（runAutomatic=true 时后端 putAll 合并进证据顶层，与 type 无关） -->
    <template v-if="item.display || item.stages">
      <v-chip size="x-small" label color="info" variant="tonal" class="mt-1">自动取证结果</v-chip>
      <div v-if="display.found?.length" class="text-body-small text-success mt-1">
        查到：{{ display.found.join('；') }}
      </div>
      <div v-if="display.notFound?.length" class="text-body-small text-error mt-1">
        未查到：{{ display.notFound.join('；') }}
      </div>
      <div v-if="display.unfinished?.length" class="text-body-small text-warning mt-1">
        未完成：{{ display.unfinished.join('；') }}
      </div>
      <p v-if="display.conclusion" class="text-body-small text-medium-emphasis mt-1 mb-0">
        结论：{{ display.conclusion }}
      </p>
      <p v-if="display.nextAction" class="text-body-small text-medium-emphasis mb-0">
        建议：{{ display.nextAction }}
      </p>
      <div v-if="item.stages?.length" class="mt-1">
        <div
          v-for="(stage, idx) in item.stages"
          :key="idx"
          class="text-body-small text-medium-emphasis"
        >
          · {{ str(stage.stage) }}
          <span v-if="stage.status"> [{{ str(stage.status) }}]</span>
          <v-expansion-panels v-if="stage.sql" variant="accordion" density="compact" class="mt-1">
            <v-expansion-panel>
              <v-expansion-panel-title class="text-body-small py-1"
                >取证 SQL</v-expansion-panel-title
              >
              <v-expansion-panel-text>
                <div class="d-flex justify-end mb-1">
                  <SqlExecuteButton
                    :sql="stage.sql"
                    :database-role="stageRole(stage)"
                    :rule-id="ruleId"
                    :profile-id="profileId"
                    :stat-start="statStart"
                    :stat-end="statEnd"
                    size="x-small"
                  />
                </div>
                <pre class="sql-pre">{{ stage.sql }}</pre>
              </v-expansion-panel-text>
            </v-expansion-panel>
          </v-expansion-panels>
        </div>
      </div>
    </template>
  </div>
</template>

<style lang="scss" scoped>
.evidence-item {
  width: 100%;
}
.analysis-box {
  background: rgba(var(--v-theme-info), 0.08);
  border-radius: 6px;
}
.sql-pre {
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 12px;
  background: rgb(var(--v-theme-surface-variant));
  border-radius: 6px;
  padding: 8px;
  margin: 0;
}
</style>
