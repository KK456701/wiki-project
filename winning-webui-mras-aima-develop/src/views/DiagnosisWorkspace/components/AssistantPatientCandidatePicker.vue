<script setup lang="ts">
import { computed } from 'vue';
import { PATIENT_CLARIFICATION_DIRECTION, PATIENT_LOOKUP_MODE } from '@/constants/diagnosis';
import type {
  PatientCandidate,
  PatientClarificationDirection,
  PatientLookupMode,
} from '@/types/diagnosis';
import type { PatientOption } from '@/views/DiagnosisWorkspace/assistant';
import { usePatientCandidateSearch } from '@/views/DiagnosisWorkspace/composables/usePatientCandidateSearch';

const props = defineProps<{ caseId: string; busy: boolean }>();
const emit = defineEmits<{ select: [option: PatientOption] }>();
const search = usePatientCandidateSearch(props.caseId);

const directionItems = [
  { title: '统计多了', value: PATIENT_CLARIFICATION_DIRECTION.OVER_COUNTED },
  { title: '统计少了', value: PATIENT_CLARIFICATION_DIRECTION.UNDER_COUNTED },
] satisfies Array<{ title: string; value: PatientClarificationDirection }>;

const lookupItems = [
  { title: '姓名 / 床位号', value: PATIENT_LOOKUP_MODE.NAME_BED },
  { title: '住院号 / 入院日期', value: PATIENT_LOOKUP_MODE.IMRN_ADMISSION_DATE },
  { title: '患者就诊 ID', value: PATIENT_LOOKUP_MODE.ENCOUNTER_ID },
  { title: '姓名 / 住院号', value: PATIENT_LOOKUP_MODE.NAME_IMRN },
] satisfies Array<{ title: string; value: PatientLookupMode }>;

const searchLabel = computed(() => {
  switch (search.lookupMode.value) {
    case PATIENT_LOOKUP_MODE.IMRN_ADMISSION_DATE:
      return '选择或搜索住院号、入院日期';
    case PATIENT_LOOKUP_MODE.ENCOUNTER_ID:
      return '选择或搜索患者就诊 ID';
    case PATIENT_LOOKUP_MODE.NAME_IMRN:
      return '选择或搜索姓名、住院号';
    default:
      return '选择或搜索姓名、床位号';
  }
});

function membershipLabel(item: PatientCandidate): string {
  if (item.numeratorPresent) return '已进入分子';
  if (item.denominatorPresent) return '仅进入分母';
  if (item.targetPresent) return '仅在抽取结果';
  return '仅在业务源';
}

function candidateTitle(item: PatientCandidate): string {
  return [item.fullName || item.encounterId, item.imrn && `住院号 ${item.imrn}`]
    .filter(Boolean)
    .join(' · ');
}

function selectCandidate(item: PatientCandidate | null) {
  if (!item) return;
  const title = item.fullName || item.encounterId;
  emit('select', {
    value: item.encounterId,
    title,
    subtitle: [
      item.encounterId,
      item.imrn && `住院号 ${item.imrn}`,
      item.bedNo && `床位 ${item.bedNo}`,
      membershipLabel(item),
    ]
      .filter(Boolean)
      .join(' / '),
    displayLabel: [title, item.departmentName].filter(Boolean).join(' · '),
    row: { ...item.row, ENCOUNTER_ID: item.encounterId },
    direction: search.direction.value,
  });
}
</script>

<template>
  <div class="d-flex flex-column ga-3">
    <v-btn-toggle
      :model-value="search.direction.value"
      mandatory
      density="compact"
      variant="outlined"
      color="primary"
      @update:model-value="search.setDirection($event)"
    >
      <v-btn v-for="item in directionItems" :key="item.value" :value="item.value" size="small">
        {{ item.title }}
      </v-btn>
    </v-btn-toggle>

    <v-select
      :model-value="search.lookupMode.value"
      :items="lookupItems"
      label="搜索方式"
      variant="outlined"
      density="compact"
      hide-details
      @update:model-value="search.setLookupMode($event)"
    />

    <v-autocomplete
      :model-value="null"
      :search="search.keyword.value"
      :items="search.items.value"
      :item-title="candidateTitle"
      item-value="encounterId"
      :label="searchLabel"
      :loading="search.loading.value"
      :disabled="props.busy"
      :error="!search.keywordValid.value || Boolean(search.error.value)"
      :error-messages="search.error.value"
      :no-data-text="search.emptyReason.value || '没有匹配的患者'"
      variant="outlined"
      density="compact"
      hide-details="auto"
      return-object
      no-filter
      clearable
      :menu-props="{ maxHeight: 360 }"
      @update:search="search.scheduleSearch($event ?? '')"
      @update:model-value="selectCandidate($event)"
    >
      <template #item="{ props: itemProps, item }">
        <v-list-item v-bind="itemProps" lines="two">
          <template #subtitle>
            {{
              [item.encounterId, item.bedNo && `床位 ${item.bedNo}`, item.admittedAt]
                .filter(Boolean)
                .join(' / ')
            }}
          </template>
          <template #append>
            <v-chip color="primary" variant="tonal" size="x-small">
              {{ membershipLabel(item) }}
            </v-chip>
          </template>
        </v-list-item>
      </template>
    </v-autocomplete>

    <div class="text-body-small text-medium-emphasis">
      {{ search.hint.value }}
    </div>
    <v-alert
      v-if="search.warning.value"
      type="warning"
      variant="tonal"
      density="compact"
      class="text-body-small"
    >
      {{ search.warning.value }}
    </v-alert>
  </div>
</template>
