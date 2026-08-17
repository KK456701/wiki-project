<script setup lang="ts">
import { computed } from 'vue';
import type { ValidationItem } from '@/types/chat';
import {
  computeImpactLevel,
  categoryLabel,
  profileWindowCount,
  evidenceTotal,
} from '../composables/useInitializationDetail';
import InitializationValidationItem from './InitializationValidationItem.vue';

const props = defineProps<{
  level: string;
  levelLabel: string;
  items: ValidationItem[];
  groupedByRule: {
    ruleId: string;
    ruleName: string;
    profileLabel: string;
    profileId: string;
    items: ValidationItem[];
  }[];
  profiles: { profileId: string; businessSourceCount?: number }[];
}>();

const emit = defineEmits<{
  (e: 'focusExec', profileId: string): void;
}>();

const distinctProfileCount = computed(() => new Set(props.items.map((i) => i.profileId)).size);

function categoryItemsInLevel(
  groupItems: ValidationItem[],
  level: string,
): Record<string, ValidationItem[]> {
  const acc: Record<string, ValidationItem[]> = {};
  for (const item of groupItems) {
    if (computeImpactLevel(item) !== level) continue;
    const c = item.category;
    if (!acc[c]) acc[c] = [];
    acc[c].push(item);
  }
  return acc;
}
</script>

<template>
  <v-expansion-panels variant="accordion" class="v-card--flat">
    <v-expansion-panel>
      <v-expansion-panel-title class="text-body-medium font-weight-medium">
        {{ levelLabel }}（{{ items.length }} 条检查，{{ distinctProfileCount }} 条口径）
      </v-expansion-panel-title>
      <v-expansion-panel-text>
        <div v-for="group in groupedByRule" :key="group.ruleId" class="mb-3">
          <template v-if="group.items.some((i) => computeImpactLevel(i) === level)">
            <div class="text-body-medium font-weight-medium mb-1">
              {{ group.ruleId }} · {{ group.ruleName }}
            </div>
            <div class="text-body-small text-medium-emphasis mb-2">
              {{ group.profileLabel || '默认口径' }}
              <template v-if="profileWindowCount(group.profileId, profiles) != null">
                · 本次统计窗口源记录
                {{ profileWindowCount(group.profileId, profiles)?.toLocaleString() }} 条
              </template>
              ·
              {{
                new Set(
                  group.items.filter((i) => computeImpactLevel(i) === level).map((i) => i.category),
                ).size
              }}
              类问题 ·
              {{ evidenceTotal(group.items.filter((i) => computeImpactLevel(i) === level)) }}
              条证据
            </div>
            <div
              v-for="(catItems, cat) in categoryItemsInLevel(group.items, level)"
              :key="cat"
              class="mb-2"
            >
              <div class="text-body-small font-weight-medium mb-1">
                {{ categoryLabel(cat) }} [{{ catItems.length }}]
              </div>
              <InitializationValidationItem
                v-for="(item, idx) in catItems"
                :key="idx"
                :item="item"
                @focus-exec="emit('focusExec', item.profileId)"
              />
            </div>
          </template>
        </div>
      </v-expansion-panel-text>
    </v-expansion-panel>
  </v-expansion-panels>
</template>
