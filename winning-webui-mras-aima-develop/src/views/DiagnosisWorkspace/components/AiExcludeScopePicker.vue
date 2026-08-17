<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useAiExcludeScope } from '@/views/DiagnosisWorkspace/composables/useAiExcludeScope';
import type { AiPatientOption, AiScopeTarget } from '@/types/diagnosis';

const props = defineProps<{
  caseId: string | null;
}>();

const emit = defineEmits<{
  (e: 'update:targets', targets: AiScopeTarget[]): void;
}>();

const {
  aiScopeMode,
  aiPatientSearch,
  filteredPatientOptions,
  aiSelectedPatients,
  aiDepartmentSearch,
  aiSelectedDepartments,
  departmentOptions,
  filteredDepartments,
  summaryText,
  loading,
  error,
  scopeTargets,
  searchPatients,
  togglePatient,
  toggleDepartment,
  clearScope,
  loadDepartmentOptions,
  importConfirmationScope,
} = useAiExcludeScope(() => props.caseId);

const expanded = ref(false);

const hasSelection = computed(
  () => aiSelectedPatients.value.length > 0 || aiSelectedDepartments.value.length > 0,
);

watch(
  () => props.caseId,
  () => {
    expanded.value = false;
  },
);

watch(scopeTargets, (targets) => emit('update:targets', targets), { deep: true });

// 进入第 3 步时，把第 2 步数据确认固化的「数据多了」患者 / 科室自动带入
// 排除对象选择（对齐 readonly importConfirmationScope 的进入 lineage 自动带入行为）。
onMounted(() => {
  importConfirmationScope();
});

function isPatientSelected(option: AiPatientOption): boolean {
  return aiSelectedPatients.value.some((item) => item.value === option.value);
}

function isDepartmentSelected(value: string): boolean {
  return aiSelectedDepartments.value.includes(value);
}

function onToggle() {
  if (!expanded.value) {
    loadDepartmentOptions();
  }
}

function switchMode(mode: 'PATIENT' | 'DEPARTMENT') {
  aiScopeMode.value = mode;
}
</script>

<template>
  <v-expansion-panels v-model="expanded" variant="accordion" flat>
    <v-expansion-panel @group:selected="onToggle">
      <v-expansion-panel-title class="scope-panel-title">
        <template #default>
          <div class="d-flex align-center ga-3 flex-wrap">
            <span class="text-body-medium font-weight-medium">选择排除规则</span>
            <span class="text-body-small text-medium-emphasis">{{ summaryText }}</span>
          </div>
          <v-spacer />
        </template>
      </v-expansion-panel-title>
      <v-expansion-panel-text class="scope-panel-text">
        <div class="d-flex flex-column ga-2">
          <v-btn-toggle v-model="aiScopeMode" mandatory density="comfortable" variant="outlined">
            <v-btn value="PATIENT" size="x-small" @click="switchMode('PATIENT')">按患者排除</v-btn>
            <v-btn value="DEPARTMENT" size="x-small" @click="switchMode('DEPARTMENT')"
              >按科室排除</v-btn
            >
          </v-btn-toggle>

          <!-- 按患者排除 -->
          <template v-if="aiScopeMode === 'PATIENT'">
            <div v-if="aiSelectedPatients.length" class="selected-scope d-flex flex-wrap ga-1">
              <v-chip
                v-for="item in aiSelectedPatients"
                :key="item.value"
                size="small"
                color="primary"
                variant="tonal"
                closable
                @click:close="togglePatient(item)"
              >
                {{ item.label }}
              </v-chip>
            </div>
            <div class="d-flex ga-2">
              <v-text-field
                v-model="aiPatientSearch"
                density="compact"
                hide-details
                placeholder="输入姓名、就诊号或住院号"
                @keyup.enter="searchPatients"
              />
              <v-btn
                variant="tonal"
                color="primary"
                size="small"
                :loading="loading"
                :disabled="!aiPatientSearch.trim()"
                @click="searchPatients"
              >
                {{ loading ? '查询中…' : '查询' }}
              </v-btn>
            </div>
            <div v-if="error" class="text-body-small text-error">{{ error }}</div>
            <div v-if="filteredPatientOptions.length" class="scope-options">
              <v-checkbox-btn
                v-for="item in filteredPatientOptions"
                :key="item.value"
                :model-value="isPatientSelected(item)"
                density="compact"
                class="scope-option"
                @update:model-value="togglePatient(item)"
              >
                <template #label>
                  <span class="text-body-medium">{{ item.label }}</span>
                </template>
              </v-checkbox-btn>
            </div>
            <p v-else class="text-body-small text-medium-emphasis mb-0">
              输入关键词后查询本次分子、分母明细。
            </p>
          </template>

          <!-- 按科室排除 -->
          <template v-else>
            <div v-if="aiSelectedDepartments.length" class="selected-scope d-flex flex-wrap ga-1">
              <v-chip
                v-for="value in aiSelectedDepartments"
                :key="value"
                size="small"
                color="primary"
                variant="tonal"
                closable
                @click:close="toggleDepartment(value)"
              >
                {{ departmentOptions.find((item) => item.value === value)?.label ?? value }}
              </v-chip>
            </div>
            <v-text-field
              v-model="aiDepartmentSearch"
              density="compact"
              hide-details
              placeholder="搜索科室名称或编码"
            />
            <div v-if="filteredDepartments.length" class="scope-options">
              <v-checkbox-btn
                v-for="item in filteredDepartments"
                :key="item.value"
                :model-value="isDepartmentSelected(item.value)"
                density="compact"
                class="scope-option"
                @update:model-value="toggleDepartment(item.value)"
              >
                <template #label>
                  <span class="text-body-medium">{{ item.label }}</span>
                  <span class="text-body-small text-medium-emphasis">
                    分母 {{ item.denominatorCount }} · 分子 {{ item.numeratorCount }}
                  </span>
                </template>
              </v-checkbox-btn>
            </div>
            <p v-else class="text-body-small text-medium-emphasis mb-0">
              暂无科室选项，请先完成数据确认。
            </p>
          </template>

          <div class="d-flex justify-end ga-2">
            <v-btn
              variant="text"
              size="x-small"
              color="primary"
              prepend-icon="mdi-import"
              @click="importConfirmationScope"
            >
              带入数据确认
            </v-btn>
            <v-btn
              variant="text"
              size="x-small"
              color="error"
              :disabled="!hasSelection"
              @click="clearScope"
            >
              清空选择
            </v-btn>
          </div>
        </div>
      </v-expansion-panel-text>
    </v-expansion-panel>
  </v-expansion-panels>
</template>

<style lang="scss" scoped>
.scope-options {
  max-height: 240px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.scope-option {
  margin: 0;
}

.scope-panel-title {
  min-height: 40px;
  padding: 8px 12px;
}

.scope-panel-text :deep(.v-expansion-panel-text__wrapper) {
  padding: 8px 12px 10px;
}
</style>
