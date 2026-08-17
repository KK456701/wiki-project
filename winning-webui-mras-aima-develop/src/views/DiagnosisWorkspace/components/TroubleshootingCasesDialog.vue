<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useDisplay } from 'vuetify';
import type { TroubleshootingCaseCategory, TroubleshootingCaseItem } from '@/types/diagnosis';
import { renderMarkdown } from '@/utils/markdown';
import { troubleshootingCaseSummary } from '@/views/DiagnosisWorkspace/troubleshooting-cases';

const props = defineProps<{
  category: TroubleshootingCaseCategory | null;
  indicatorName: string;
  profileName: string;
}>();

const open = defineModel<boolean>({ default: false });
const expanded = ref<string[]>([]);
const { mdAndDown } = useDisplay();
const title = computed(() => props.category?.name ?? '历史问题案例');

watch(
  () => props.category?.name,
  () => (expanded.value = []),
);

function rendered(value: string): string {
  return renderMarkdown(value);
}

function summary(value: TroubleshootingCaseItem): string {
  return troubleshootingCaseSummary(value.problemDescription);
}
</script>

<template>
  <v-dialog v-model="open" :fullscreen="mdAndDown" max-width="960" scrollable>
    <v-card rounded="lg">
      <v-toolbar density="compact" color="surface">
        <v-icon icon="mdi-archive-search-outline" color="primary" class="ml-4" />
        <v-toolbar-title class="text-title-medium font-weight-medium">
          {{ title }} · {{ category?.count ?? 0 }} 条
        </v-toolbar-title>
        <v-btn icon="mdi-close" variant="text" aria-label="关闭历史案例" @click="open = false" />
      </v-toolbar>

      <div class="case-context px-5 py-3 text-body-small text-medium-emphasis">
        <span>{{ indicatorName }}</span>
        <span v-if="profileName">{{ profileName }}</span>
      </div>

      <v-card-text class="history-dialog-body pa-0">
        <v-expansion-panels v-model="expanded" multiple variant="accordion" flat>
          <v-expansion-panel
            v-for="item in category?.cases ?? []"
            :key="item.problemNumber"
            :value="item.problemNumber"
          >
            <v-expansion-panel-title>
              <div class="case-heading">
                <strong class="text-body-medium font-weight-medium">{{ summary(item) }}</strong>
                <div class="case-meta text-body-small text-medium-emphasis">
                  <span>TFS {{ item.tfsNumber }}</span>
                  <span>{{ item.date }}</span>
                  <span>{{ item.handler }}</span>
                </div>
              </div>
            </v-expansion-panel-title>
            <v-expansion-panel-text>
              <!-- markdown-it 禁用原始 HTML，以下仅渲染已转义的知识库 Markdown。 -->
              <!-- eslint-disable vue/no-v-html -->
              <section class="case-section">
                <h3 class="text-label-large font-weight-medium">问题描述</h3>
                <div
                  class="markdown-body text-body-medium"
                  v-html="rendered(item.problemDescription)"
                />
              </section>
              <section class="case-section">
                <h3 class="text-label-large font-weight-medium">根因定位</h3>
                <div class="markdown-body text-body-medium" v-html="rendered(item.rootCause)" />
              </section>
              <section class="case-section">
                <h3 class="text-label-large font-weight-medium">处理情况与修复方案</h3>
                <div class="markdown-body text-body-medium" v-html="rendered(item.solution)" />
              </section>
              <!-- eslint-enable vue/no-v-html -->
            </v-expansion-panel-text>
          </v-expansion-panel>
        </v-expansion-panels>
      </v-card-text>
    </v-card>
  </v-dialog>
</template>

<style lang="scss" scoped>
@use '@/views/ChatView/components/styles/markdown-body';

.case-context {
  display: flex;
  gap: 16px;
  border-block: 1px solid rgba(var(--v-border-color), var(--v-border-opacity));
}

.history-dialog-body {
  max-height: min(72vh, 720px);
  overflow-y: auto;
}

.case-heading {
  display: grid;
  flex: 1;
  gap: 6px;
  min-width: 0;
}

.case-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 16px;
}

.case-section {
  padding: 4px 0 16px;

  & + & {
    padding-top: 16px;
    border-top: 1px solid rgba(var(--v-border-color), var(--v-border-opacity));
  }

  h3 {
    margin: 0 0 8px;
    color: rgb(var(--v-theme-primary));
  }
}
</style>
