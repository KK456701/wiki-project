<script setup lang="ts">
import { computed } from 'vue';
import type { BatchResultItem } from '@/types/chat';
import { useBatchResults } from '../composables/useBatchResults';
import IndicatorCardRow from './IndicatorCardRow.vue';

const props = defineProps<{
  batchResults: BatchResultItem[];
}>();

const resultsRef = computed(() => props.batchResults);

const { indicatorGroupList, getRecommendedProfile, getCardStatus, getCardAdvice } =
  useBatchResults(resultsRef);

const STATUS_COLOR = {
  计算成功: 'success',
  无样本: 'grey',
} as const;
</script>

<template>
  <div class="indicator-cards">
    <v-expansion-panels multiple>
      <v-expansion-panel v-for="group in indicatorGroupList" :key="group[0].ruleId" class="mb-2">
        <v-expansion-panel-title>
          <div class="d-flex align-center ga-2 w-100">
            <v-icon
              :icon="getCardStatus(group) === '计算成功' ? 'mdi-check-circle' : 'mdi-alert-circle'"
              :color="STATUS_COLOR[getCardStatus(group) as keyof typeof STATUS_COLOR] || 'error'"
              size="18"
            />
            <span class="text-body-medium font-weight-medium flex-grow-1">
              {{ group[0].ruleName }}
            </span>
            <v-chip size="x-small" variant="tonal" class="flex-shrink-0">
              {{ group[0].ruleId }}
            </v-chip>
            <span class="text-body-small text-medium-emphasis flex-shrink-0">
              {{ getCardStatus(group) }}
            </span>
          </div>
        </v-expansion-panel-title>

        <v-expansion-panel-text>
          <!-- 口径行列表 -->
          <div v-for="item in group" :key="(item.profileId ?? '') + item.ruleId" class="mb-1">
            <IndicatorCardRow
              :item="item"
              :is-recommended="item === getRecommendedProfile(group)"
            />
          </div>

          <!-- 系统建议 -->
          <div class="text-body-small text-medium-emphasis bg-surface rounded pa-2 mt-2">
            <v-icon icon="mdi-lightbulb-outline" size="14" class="mr-1" />
            {{ getCardAdvice(group) }}
          </div>
        </v-expansion-panel-text>
      </v-expansion-panel>
    </v-expansion-panels>
  </div>
</template>
