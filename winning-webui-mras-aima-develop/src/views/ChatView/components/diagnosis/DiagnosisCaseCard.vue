<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { format, parseISO } from 'date-fns';
import { useDiagnosisStore } from '@/stores/diagnosis';
import { DIAGNOSIS_STEP_LABELS, STEP_STATUS_COLOR } from './diagnosis-constants';

const props = defineProps<{ caseId: string }>();

const router = useRouter();
const diagnosisStore = useDiagnosisStore();

const snapshot = computed(() => diagnosisStore.getCase(props.caseId));
const loading = computed(() => diagnosisStore.isCaseLoading(props.caseId));
const loadError = ref('');

const statusColor = computed(() =>
  snapshot.value ? (STEP_STATUS_COLOR[snapshot.value.status] ?? 'grey') : 'grey',
);
const stepLabel = computed(() =>
  snapshot.value
    ? (DIAGNOSIS_STEP_LABELS[snapshot.value.currentStep] ?? snapshot.value.currentStep)
    : '',
);

function formatPeriod(iso?: string): string {
  if (!iso) return '';
  try {
    return format(parseISO(iso), 'yyyy-MM-dd');
  } catch {
    return iso;
  }
}

const periodText = computed(() => {
  const cal = snapshot.value?.caliberSnapshot;
  const start =
    cal?.timeRange?.start || (snapshot.value?.caseInput?.statStart as string | undefined);
  const end = cal?.timeRange?.end || (snapshot.value?.caseInput?.statEnd as string | undefined);
  if (!start || !end) return '';
  return `${formatPeriod(String(start))} ~ ${formatPeriod(String(end))}`;
});

function openWorkspace() {
  router.push({ path: '/diagnosis', query: { caseId: props.caseId } });
}

onMounted(async () => {
  if (!diagnosisStore.getCase(props.caseId)) {
    try {
      await diagnosisStore.loadCase(props.caseId);
    } catch (e) {
      loadError.value = e instanceof Error ? e.message : '加载排查任务失败';
    }
  }
});
</script>

<template>
  <v-card variant="outlined" class="diagnosis-entry mt-2" border>
    <v-card-text class="pa-4">
      <div v-if="loading" class="d-flex align-center ga-2 text-medium-emphasis">
        <v-progress-circular indeterminate size="20" width="3" color="primary" />
        正在加载排查任务…
      </div>

      <v-alert
        v-else-if="loadError"
        type="error"
        variant="tonal"
        density="comfortable"
        class="mb-0"
      >
        {{ loadError }}
      </v-alert>

      <template v-else-if="snapshot">
        <div class="d-flex flex-wrap align-center ga-2 mb-3">
          <v-icon icon="mdi-magnify-scan" color="warning" size="22" />
          <span class="text-body-large font-weight-bold">{{
            snapshot.caliberSnapshot.ruleName
          }}</span>
          <v-chip v-if="snapshot.caliberSnapshot.profileName" size="small" label variant="tonal">
            {{ snapshot.caliberSnapshot.profileName }}
          </v-chip>
          <v-spacer />
          <v-chip :color="statusColor" size="small" label variant="flat">{{
            snapshot.status
          }}</v-chip>
          <v-chip size="small" label variant="tonal">{{ stepLabel }}</v-chip>
        </div>

        <div class="text-body-small text-medium-emphasis mb-3">
          <v-icon icon="mdi-calendar-range" size="14" class="mr-1" />
          {{ periodText || '统计周期未知' }}
        </div>

        <v-btn
          color="warning"
          variant="flat"
          prepend-icon="mdi-open-in-new"
          block
          @click="openWorkspace"
        >
          打开排查工作区
        </v-btn>
      </template>
    </v-card-text>
  </v-card>
</template>

<style lang="scss" scoped>
.diagnosis-entry {
  border-color: rgb(var(--v-theme-warning));
}
</style>
