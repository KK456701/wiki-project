<script setup lang="ts">
import type { ValidationItem } from '@/types/chat';
import { computed } from 'vue';
import SqlExecuteButton from '@/components/SqlExecuteButton.vue';
import { inferSqlDatabaseRole } from '@/services/sql-preview';
import {
  databaseLabel,
  scopeLabel,
  fieldRolesLabel,
  impactText,
} from '../composables/useInitializationDetail';

const props = defineProps<{
  item: ValidationItem;
}>();

defineEmits<{
  (e: 'focusExec', profileId: string): void;
}>();

function toPercent(rate: number | undefined): string {
  if (rate == null) return '';
  return `${(rate * 100).toFixed(1)}%`;
}

const sqlDatabaseRole = computed(() =>
  inferSqlDatabaseRole(props.item.sql ?? '', props.item.databaseRole),
);

function parameterValue(...names: string[]): string | undefined {
  for (const name of names) {
    const value = props.item.parameters?.[name];
    if (typeof value === 'string') return value;
  }
  return undefined;
}
</script>

<template>
  <div class="validation-item bg-surface rounded mb-2 border-s">
    <v-expansion-panels variant="accordion" class="v-card--flat">
      <v-expansion-panel>
        <v-expansion-panel-title class="pa-3 text-body-medium">
          <div class="d-flex align-center w-100">
            <span class="font-weight-medium"
              >{{ databaseLabel(item.databaseRole) }} · {{ item.issueSummary }}</span
            >
            <v-spacer />
            <v-chip
              size="x-small"
              :color="item.severity === 'BLOCKED' ? 'error' : ''"
              variant="tonal"
              class="ml-2"
            >
              {{ item.action }}
            </v-chip>
          </div>
        </v-expansion-panel-title>
        <v-expansion-panel-text>
          <!-- 指标定位按钮 -->
          <div class="text-body-small mb-2">
            <v-btn
              variant="text"
              size="x-small"
              class="text-body-medium pa-0"
              @click="$emit('focusExec', item.profileId)"
            >
              {{ item.ruleId }} · {{ item.ruleName }}
            </v-btn>
            <span class="text-medium-emphasis">｜{{ item.profileLabel || item.profileId }}</span>
          </div>

          <!-- 修复建议 -->
          <v-card v-if="item.repairSuggestion" variant="outlined" class="mb-2 pa-2 bg-info-light">
            <div class="text-body-small font-weight-medium mb-1">
              建议怎么修 {{ item.repairOwner ? `· ${item.repairOwner}` : '' }}
            </div>
            <div class="text-body-small">{{ item.repairSuggestion }}</div>
            <div
              v-if="item.knowledgePatchTemplate"
              class="text-body-small text-medium-emphasis mt-1"
            >
              {{ item.knowledgePatchTemplate }}
            </div>
          </v-card>

          <!-- 字段详情 -->
          <div class="text-body-small">
            <div>
              对象：<code>{{ item.tableName }}.{{ item.fieldName }}</code>
            </div>
            <div v-if="item.sourceSystem || item.queryScope">
              来源/范围：{{ item.sourceSystem ?? '—' }} · {{ scopeLabel(item.queryScope) }}
            </div>
            <div v-if="item.fieldRoles?.length">
              字段作用：{{ fieldRolesLabel(item.fieldRoles) }}
            </div>
            <div v-if="item.unresolvedSymbols?.length">
              未解析字段：<code v-for="sym in item.unresolvedSymbols" :key="sym" class="mr-1">{{
                sym
              }}</code>
            </div>
            <div v-if="item.actualCount != null">
              实际数量：{{ item.actualCount.toLocaleString() }}
            </div>
            <div v-if="item.nullCount != null && item.totalCount != null">
              空值：{{ item.nullCount.toLocaleString() }} /
              {{ item.totalCount.toLocaleString() }}（{{ toPercent(item.rate) }}）
            </div>
            <div v-if="item.matchedCount != null && item.totalCount != null">
              关联覆盖：{{ item.matchedCount.toLocaleString() }} /
              {{ item.totalCount.toLocaleString() }}，未匹配
              {{ item.unmatchedCount?.toLocaleString() ?? '—' }}（{{ toPercent(item.rate) }}）
            </div>
            <div>影响判断：{{ impactText(item) }}</div>
            <div v-if="item.message">原因：{{ item.message }}</div>
            <div v-if="item.errorCode">
              错误码：<code>{{ item.errorCode }}</code>
            </div>
          </div>

          <!-- 校验 SQL -->
          <div v-if="item.sql" class="mt-2">
            <v-expansion-panels variant="accordion" density="compact" class="v-card--flat">
              <v-expansion-panel>
                <v-expansion-panel-title class="text-body-small py-1">
                  查看校验 SQL
                </v-expansion-panel-title>
                <v-expansion-panel-text>
                  <div class="text-body-small text-medium-emphasis mb-1">
                    用途：初始化阶段生成的只读聚合探针，不修改数据
                  </div>
                  <div class="text-body-small mb-1">
                    {{ databaseLabel(item.databaseRole) }} · {{ item.durationMs ?? '—' }}ms · 返回
                    {{ item.returnedRows ?? '—' }} 行
                  </div>
                  <div class="d-flex justify-end mb-1">
                    <SqlExecuteButton
                      :sql="item.sql"
                      :database-role="sqlDatabaseRole"
                      :rule-id="item.ruleId"
                      :profile-id="item.profileId"
                      :stat-start="parameterValue('start_time', 'startTime', 'statStart')"
                      :stat-end="parameterValue('end_time', 'endTime', 'statEnd')"
                      size="x-small"
                    />
                  </div>
                  <pre
                    class="text-body-small bg-surface rounded pa-2 overflow-auto font-monospace"
                    style="max-height: 200px"
                    >{{ item.sql }}</pre>
                  <div v-if="item.parameters" class="text-body-small mt-1 font-monospace">
                    参数：{{ JSON.stringify(item.parameters, null, 2) }}
                  </div>
                  <div v-if="item.databaseError" class="text-body-small text-error mt-1">
                    ⚠ {{ item.databaseError }}
                  </div>
                </v-expansion-panel-text>
              </v-expansion-panel>
            </v-expansion-panels>
          </div>
        </v-expansion-panel-text>
      </v-expansion-panel>
    </v-expansion-panels>
  </div>
</template>

<style lang="scss" scoped>
.validation-item {
  border-left: 3px solid rgba(var(--v-theme-primary), 0.3);
  // font-size: 14px;
}

.border-s {
  border-left-width: 2px;
}

.bg-info-light {
  background-color: rgba(var(--v-theme-info), 0.06);
}
</style>
