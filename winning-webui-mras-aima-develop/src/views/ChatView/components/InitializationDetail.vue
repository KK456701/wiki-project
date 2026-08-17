<script setup lang="ts">
import { computed, ref } from 'vue';
import type { TraceNode, InitializationOutput } from '@/types/chat';
import { statusText, durationStr } from '../composables/useExecutionSteps';
import {
  useInitializationDetail,
  qualityText,
  parseSnapshotOutput,
} from '../composables/useInitializationDetail';
import InitializationImpactGroups from './InitializationImpactGroups.vue';

const props = defineProps<{
  node: TraceNode;
  initOutputData: Record<string, unknown> | null;
  allTraceNodes: TraceNode[];
}>();

const initData = computed<InitializationOutput | null>(
  () => props.initOutputData as InitializationOutput | null,
);

const {
  searchText,
  dbFilter,
  typeFilter,
  impactLevels,
  impactLevelItems,
  impactProfileCount,
  realSnapshotNodes,
  realSnapshotSummary,
  focusedExecutionNode,
  groupedByRule,
  CATEGORY_LABELS,
} = useInitializationDetail(
  () => initData.value,
  () => props.allTraceNodes,
);

const focusedExec = ref<TraceNode | null>(null);

function handleFocusExec(profileId: string) {
  focusedExec.value = focusedExecutionNode(profileId);
}

const hasInitData = computed(() =>
  props.initOutputData ? Number(props.initOutputData.profileCount ?? 0) > 0 : false,
);

const REAL_SNAPSHOT_PANEL = 0;

const LEVEL_LABELS: Record<string, string> = {
  CONFIRMED: '确定影响计算',
  POSSIBLE: '可能影响结果',
  DISPLAY_ONLY: '仅影响明细展示',
  UNKNOWN: '无法判断',
};

const STATS = [
  { label: '可继续', key: 'runnableCount' as const, color: 'success' },
  { label: '无样本', key: 'noSampleCount' as const, color: 'warning' },
  { label: '被阻断', key: 'blockedCount' as const, color: 'error' },
  { label: '未实现', key: 'skippedCount' as const, color: '' },
  { label: '缺表', key: 'missingTableCount' as const, color: 'warning' },
  { label: '缺字段', key: 'missingColumnCount' as const, color: 'warning' },
  { label: '无数据', key: 'emptySourceCount' as const, color: '' },
];
</script>

<template>
  <!-- 待处理态 -->
  <v-card v-if="initOutputData && !hasInitData" variant="outlined" class="mb-4 text-center py-6">
    <v-icon icon="mdi-clock-outline" size="48" color="primary" class="mb-3" />
    <p class="text-body-large font-weight-medium mb-1">双库初始化校验</p>
    <p class="text-body-medium text-medium-emphasis">
      正在逐表检查业务库与真实库，完成前先显示进度
    </p>
  </v-card>

  <template v-if="initData && hasInitData">
    <!-- 质量状态 Hero -->
    <v-card variant="outlined" class="mb-4">
      <v-card-text class="pa-3 text-center">
        <div class="text-body-small text-medium-emphasis">Dual database preflight</div>
        <div class="text-headline-medium font-weight-bold my-1">
          {{ qualityText(initData.qualityStatus) }}
        </div>
        <p class="text-body-medium text-medium-emphasis mb-1">
          业务源库检查数据质量，真实库先核对结构；抽取完成后在同一处补齐本次快照验证
        </p>
        <v-chip size="small" variant="tonal">{{ initData.profileCount }} 个口径</v-chip>
      </v-card-text>
    </v-card>

    <!-- 7 项统计 -->
    <div class="d-flex justify-space-between ga-2 mb-4 overflow-x-auto pb-1">
      <div v-for="stat in STATS" :key="stat.label" class="text-center stat-chip">
        <div
          class="text-headline-small font-weight-bold"
          :class="stat.color ? `text-${stat.color}` : ''"
        >
          {{ initData[stat.key] ?? '--' }}
        </div>
        <div class="text-body-small text-medium-emphasis">{{ stat.label }}</div>
      </div>
    </div>

    <!-- 影响分级 -->
    <div class="d-flex flex-wrap ga-2 mb-4">
      <v-card
        v-for="level in impactLevels"
        :key="level"
        variant="outlined"
        :color="
          level === 'CONFIRMED'
            ? 'error'
            : level === 'POSSIBLE'
              ? 'warning'
              : level === 'DISPLAY_ONLY'
                ? 'info'
                : ''
        "
        class="flex-1-1-0 pa-2 text-center"
        min-width="100"
      >
        <div class="text-body-small text-medium-emphasis">
          {{ impactProfileCount(level) }} 个口径
        </div>
        <div class="text-body-medium font-weight-medium">
          {{ LEVEL_LABELS[level] ?? level }}
        </div>
        <div class="text-body-small text-medium-emphasis">
          {{ impactLevelItems(level).length }} 条检查
        </div>
      </v-card>
    </div>
    <!-- 连接状态 -->
    <v-card variant="outlined" class="mb-4">
      <v-card-text class="pa-3">
        <div class="d-flex flex-wrap ga-3 text-body-medium">
          <span>
            业务库
            <v-icon
              :icon="initData.businessConnected ? 'mdi-check-circle' : 'mdi-close-circle'"
              :color="initData.businessConnected ? 'success' : 'error'"
              size="14"
            />
            {{ initData.businessConnected ? '已连接' : '不可用' }}
          </span>
          <span>
            真实库
            <v-icon
              :icon="initData.realConnected ? 'mdi-check-circle' : 'mdi-close-circle'"
              :color="initData.realConnected ? 'success' : 'error'"
              size="14"
            />
            {{ initData.realConnected ? '已连接' : '不可用' }}
          </span>
          <span>· {{ initData.reused ? '复用上次' : '本次实查 · 未复用' }}</span>
          <span>耗时 {{ durationStr(initData.durationMs) }}</span>
        </div>
      </v-card-text>
    </v-card>

    <!-- 筛选器 -->
    <div class="d-flex flex-wrap ga-2 mb-4">
      <v-text-field
        v-model="searchText"
        density="compact"
        variant="outlined"
        hide-details
        placeholder="搜索指标编码、表名或字段名"
        prepend-inner-icon="mdi-magnify"
        clearable
        class="flex-1-1-0"
        style="min-width: 200px"
      />
      <v-select
        v-model="dbFilter"
        :items="[
          { title: '全部数据库', value: 'all' },
          { title: '业务库', value: 'business' },
          { title: '真实库', value: 'real' },
        ]"
        density="compact"
        variant="outlined"
        hide-details
        style="max-width: 140px"
      />
      <v-select
        v-model="typeFilter"
        :items="[
          { title: '全部类型', value: 'all' },
          ...Object.entries(CATEGORY_LABELS).map(([k, v]) => ({ title: v, value: k })),
        ]"
        density="compact"
        variant="outlined"
        hide-details
        style="max-width: 160px"
      />
    </div>

    <!-- 校验明细：L1 影响等级 → L2 指标口径 → items -->
    <div v-for="level in impactLevels" :key="level" class="mb-3">
      <template v-if="impactLevelItems(level).length > 0">
        <InitializationImpactGroups
          :level="level"
          :level-label="LEVEL_LABELS[level] ?? level"
          :items="impactLevelItems(level)"
          :grouped-by-rule="groupedByRule"
          :profiles="initData.profiles ?? []"
          @focus-exec="handleFocusExec"
        />
      </template>
    </div>
    <!-- 指标执行定位 -->
    <v-card v-if="focusedExec" variant="outlined" class="mb-4">
      <v-card-title class="text-label-large py-2 px-3 d-flex align-center">
        指标执行定位
        <v-spacer />
        <v-btn size="x-small" variant="text" @click="focusedExec = null">关闭</v-btn>
      </v-card-title>
      <v-card-text class="pa-3 pt-0 text-body-medium">
        <div>状态：{{ statusText(focusedExec) }}</div>
        <div>耗时：{{ durationStr(focusedExec.durationMs) }}</div>
      </v-card-text>
    </v-card>
    <!-- 真实库本次数据校验 -->
    <v-card v-if="realSnapshotNodes.length > 0" variant="outlined" class="mb-4">
      <v-expansion-panels
        :model-value="realSnapshotSummary.failed > 0 ? REAL_SNAPSHOT_PANEL : undefined"
        variant="accordion"
        class="v-card--flat"
      >
        <v-expansion-panel>
          <v-expansion-panel-title class="text-label-large py-2 px-3">
            真实库本次数据校验 {{ realSnapshotSummary.completed }}/{{ realSnapshotSummary.total }}
          </v-expansion-panel-title>
          <v-expansion-panel-text>
            <div class="d-flex ga-4 mb-2">
              <v-chip size="small" variant="tonal" color="success"
                >一致 {{ realSnapshotSummary.success }}</v-chip
              >
              <v-chip size="small" variant="tonal" color="error"
                >失败 {{ realSnapshotSummary.failed }}</v-chip
              >
              <v-chip size="small" variant="tonal">等待 {{ realSnapshotSummary.waiting }}</v-chip>
            </div>
            <div v-for="sn in realSnapshotNodes" :key="sn.nodeId" class="mb-2">
              <div class="text-body-medium">
                {{ (parseSnapshotOutput(sn).ruleId as string) ?? sn.nodeId }}
              </div>
            </div>
          </v-expansion-panel-text>
        </v-expansion-panel>
      </v-expansion-panels>
    </v-card>
  </template>
</template>

<style lang="scss" scoped>
.stat-chip {
  min-width: 60px;
}
</style>
