<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { getTroubleshootingCases } from '@/services/diagnosis';
import type { TroubleshootingCaseCategory, TroubleshootingCasesResponse } from '@/types/diagnosis';
import TroubleshootingCasesDialog from '@/views/DiagnosisWorkspace/components/TroubleshootingCasesDialog.vue';

const props = defineProps<{ caseId: string }>();
const data = ref<TroubleshootingCasesResponse | null>(null);
const selected = ref<TroubleshootingCaseCategory | null>(null);
const dialogOpen = ref(false);
const loading = ref(false);
const error = ref('');
const visibleCategories = computed(() => data.value?.categories.slice(0, 5) ?? []);

async function loadCases() {
  loading.value = true;
  error.value = '';
  try {
    data.value = await getTroubleshootingCases(props.caseId);
  } catch (reason) {
    data.value = null;
    error.value = reason instanceof Error ? reason.message : '历史案例加载失败';
  } finally {
    loading.value = false;
  }
}

function openCategory(category: TroubleshootingCaseCategory) {
  selected.value = category;
  dialogOpen.value = true;
}

onMounted(loadCases);
watch(() => props.caseId, loadCases);
</script>

<template>
  <div v-if="loading" class="category-loading d-flex ga-2 mt-3" aria-label="正在加载历史案例">
    <v-skeleton-loader v-for="index in 3" :key="index" type="chip" width="112" />
  </div>

  <v-alert v-else-if="error" type="info" variant="tonal" density="compact" class="mt-3">
    <div class="d-flex align-center justify-space-between ga-2">
      <span class="text-body-small">{{ error }}</span>
      <v-btn size="small" variant="text" @click="loadCases">重试</v-btn>
    </div>
  </v-alert>

  <template v-else-if="visibleCategories.length">
    <p class="text-label-medium text-medium-emphasis mt-3 mb-0">您可以尝试查看该指标的：</p>
    <div class="case-categories d-flex ga-2 mt-2">
      <v-btn
        v-for="category in visibleCategories"
        :key="category.name"
        size="small"
        variant="flat"
        :color="dialogOpen && selected?.name === category.name ? 'primary' : undefined"
        class="category-button text-label-medium"
        :class="{ 'category-button--selected': dialogOpen && selected?.name === category.name }"
        @click="openCategory(category)"
      >
        {{ category.name }}案例
      </v-btn>
    </div>
  </template>

  <TroubleshootingCasesDialog
    v-model="dialogOpen"
    :category="selected"
    :indicator-name="data?.indicatorName ?? ''"
    :profile-name="data?.profileName ?? ''"
  />
</template>

<style lang="scss" scoped>
.category-loading {
  min-height: 32px;
}

.category-button {
  min-height: 34px;
  flex: 0 0 auto;
  color: rgba(var(--v-theme-on-surface), 0.78);
  background: rgb(var(--v-theme-surface));
  border: 1px solid rgba(var(--v-border-color), var(--v-border-opacity));
  text-transform: none;

  &--selected {
    color: rgb(var(--v-theme-on-primary));
    background: rgb(var(--v-theme-primary));
    border-color: rgb(var(--v-theme-primary));
  }
}

.case-categories {
  max-width: 100%;
  overflow-x: auto;
  padding-bottom: 2px;
  scrollbar-width: thin;
}
</style>
