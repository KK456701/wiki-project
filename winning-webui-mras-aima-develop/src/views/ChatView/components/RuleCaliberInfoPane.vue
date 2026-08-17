<script setup lang="ts">
import { computed } from 'vue';
import type { EffectiveRule } from '@/types/chat';
import { renderMarkdown } from '@/utils/markdown';

const props = defineProps<{ data: EffectiveRule }>();
const dataSourceHtml = computed(() =>
  props.data.dataSource ? renderMarkdown(props.data.dataSource) : '',
);
</script>

<template>
  <div class="d-flex flex-column ga-3">
    <v-card variant="outlined" density="compact">
      <v-card-text class="text-body-medium">
        <div class="mb-2">
          <span class="text-medium-emphasis">指标编码：</span><code>{{ data.ruleId }}</code>
        </div>
        <div class="mb-2">
          <span class="text-medium-emphasis">口径名称：</span>{{ data.profileName }}
        </div>
        <div class="mb-2">
          <span class="text-medium-emphasis">执行状态：</span>
          <v-chip
            :color="data.executionStatus === 'executable' ? 'success' : 'warning'"
            size="x-small"
            label
          >
            {{ data.executionStatus === 'executable' ? '可执行' : '仅文档' }}
          </v-chip>
        </div>
        <div v-if="data.definition" class="mb-2">
          <span class="text-medium-emphasis">指标定义：</span>{{ data.definition }}
        </div>
        <div v-if="data.formula" class="mb-2">
          <span class="text-medium-emphasis">计算公式：</span><strong>{{ data.formula }}</strong>
        </div>
        <div v-if="data.numeratorRule" class="mb-2">
          <span class="text-medium-emphasis">分子口径：</span>{{ data.numeratorRule }}
        </div>
        <div v-if="data.denominatorRule" class="mb-2">
          <span class="text-medium-emphasis">分母口径：</span>{{ data.denominatorRule }}
        </div>
        <div v-if="data.resultUnit">
          <span class="text-medium-emphasis">结果单位：</span>{{ data.resultUnit }}
        </div>
      </v-card-text>
    </v-card>

    <v-card v-if="data.ruleSource === 'mras'" variant="outlined" density="compact" title="数据链路">
      <v-card-text class="text-body-medium">
        <div v-if="data.system" class="mb-2">
          <span class="text-medium-emphasis">来源系统：</span>{{ data.system }}
        </div>
        <div v-if="data.dataSource" class="mb-2">
          <span class="text-medium-emphasis">数据来源：</span>
        </div>
        <!-- eslint-disable vue/no-v-html -->
        <div
          v-if="data.dataSource"
          class="markdown-body text-body-medium"
          v-html="dataSourceHtml"
        />
        <!-- eslint-enable vue/no-v-html -->
        <div v-if="data.caliber" class="mb-2">
          <span class="text-medium-emphasis">口径描述：</span>{{ data.caliber }}
        </div>
      </v-card-text>
    </v-card>

    <v-card
      v-if="data.calculationDefinition"
      variant="outlined"
      density="compact"
      title="计算口径定义"
    >
      <v-card-text>
        <pre class="text-body-small json-block">{{
          JSON.stringify(data.calculationDefinition, null, 2)
        }}</pre>
      </v-card-text>
    </v-card>
    <v-card v-if="data.resultContract" variant="outlined" density="compact" title="结果契约">
      <v-card-text>
        <pre class="text-body-small json-block">{{
          JSON.stringify(data.resultContract, null, 2)
        }}</pre>
      </v-card-text>
    </v-card>
    <v-alert
      v-if="data.executionBlockers.length > 0"
      type="warning"
      variant="tonal"
      density="compact"
    >
      <template #title>执行阻断</template>
      <ul class="mb-0 pl-3">
        <li v-for="blocker in data.executionBlockers" :key="blocker">{{ blocker }}</li>
      </ul>
    </v-alert>
  </div>
</template>

<style lang="scss" scoped>
@use './styles/markdown-body';

.json-block {
  white-space: pre-wrap;
  word-break: break-all;
  background: rgba(var(--v-theme-on-surface), 0.03);
  padding: 8px;
  border-radius: 4px;
  max-height: 300px;
  overflow-y: auto;
}
</style>
