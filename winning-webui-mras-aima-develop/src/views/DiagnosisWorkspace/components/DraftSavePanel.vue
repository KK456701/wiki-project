<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { useDiagnosisStore } from '@/stores/diagnosis';
import { DIAGNOSIS_ACTION } from '@/constants/diagnosis';
import type { DiagnosisCaseSnapshot } from '@/types/diagnosis';

const props = defineProps<{
  snapshot: DiagnosisCaseSnapshot | null;
  caseId: string | null;
}>();

const diagnosis = useDiagnosisStore();

const saving = ref(false);
const error = ref('');
const formOpen = ref(false);

const form = reactive({
  issueSummary: '',
  changeSummary: '',
  expectedImpact: '',
  verificationSummary: '',
});

function record(v: unknown): Record<string, unknown> {
  return v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : {};
}

const shadow = computed(() => record(props.snapshot?.shadowTrial));
const candidate = computed(() => record(props.snapshot?.candidateSql));
const draftResult = computed(() => record(props.snapshot?.draftResult));

/** 是否满足保存草稿条件：DRAFT_SAVE 步骤 + 试跑通过 + 非基线 */
const canSave = computed(() => {
  if (!props.snapshot || !props.caseId) return false;
  if (props.snapshot.currentStep !== 'DRAFT_SAVE') return false;
  if (!shadow.value.passed) return false;
  if (shadow.value.baselineOnly) return false;
  if (candidate.value.baselineOnly) return false;
  return true;
});

const alreadySaved = computed(() => Object.keys(draftResult.value).length > 0);

const canSubmit = computed(() => Object.values(form).every((v) => v.trim().length > 0));

async function saveDraft() {
  if (!props.caseId) return;
  saving.value = true;
  error.value = '';
  try {
    await diagnosis.submitAction(props.caseId, DIAGNOSIS_ACTION.SAVE_HOSPITAL_DRAFT, {
      confirmed: true,
      issueSummary: form.issueSummary.trim(),
      changeSummary: form.changeSummary.trim(),
      expectedImpact: form.expectedImpact.trim(),
      verificationSummary: form.verificationSummary.trim(),
    });
  } catch (e) {
    error.value = e instanceof Error ? e.message : '保存草稿失败';
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <div v-if="alreadySaved" class="draft-complete pa-3 rounded mt-3">
    <div class="d-flex align-center ga-2 mb-1">
      <v-icon icon="mdi-check-circle" color="success" size="small" />
      <span class="text-body-medium font-weight-medium">医院草稿已保存</span>
    </div>
    <div v-if="draftResult.draftId" class="text-body-small text-medium-emphasis">
      草稿编号：{{ draftResult.draftId }}
    </div>
    <div class="text-body-small text-medium-emphasis">未发布，不影响当前正式计算。</div>
  </div>

  <div v-else-if="canSave" class="draft-save mt-3">
    <v-btn
      v-if="!formOpen"
      color="primary"
      variant="flat"
      size="small"
      prepend-icon="mdi-content-save-outline"
      @click="formOpen = true"
    >
      核对结果无误，保存到医院草稿
    </v-btn>

    <div v-else class="pa-3 rounded border">
      <div class="d-flex align-center ga-2 mb-2">
        <span class="text-body-medium font-weight-medium">填写医院草稿说明</span>
        <v-spacer />
        <v-btn size="x-small" variant="text" @click="formOpen = false">暂不保存</v-btn>
      </div>
      <p class="text-body-small text-medium-emphasis mb-3">
        说明将随候选 SQL 一起进入知识库回收与审批，不会影响当前正式计算。
      </p>

      <v-textarea
        v-model="form.issueSummary"
        label="问题说明"
        variant="outlined"
        density="compact"
        rows="2"
        hide-details
        class="mb-2"
      />
      <v-textarea
        v-model="form.changeSummary"
        label="本次修改"
        variant="outlined"
        density="compact"
        rows="2"
        hide-details
        class="mb-2"
      />
      <v-textarea
        v-model="form.expectedImpact"
        label="预期影响"
        variant="outlined"
        density="compact"
        rows="2"
        hide-details
        class="mb-2"
      />
      <v-textarea
        v-model="form.verificationSummary"
        label="影子验证结论"
        variant="outlined"
        density="compact"
        rows="2"
        hide-details
        class="mb-2"
      />

      <v-alert
        v-if="error"
        type="error"
        variant="tonal"
        density="compact"
        class="mb-2"
        :text="error"
      />

      <v-btn
        color="primary"
        variant="flat"
        size="small"
        :loading="saving"
        :disabled="saving || !canSubmit"
        @click="saveDraft"
      >
        确认保存草稿
      </v-btn>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.border {
  border: 1px solid rgba(var(--v-border-color), 0.18);
}

.draft-complete {
  background: rgba(var(--v-theme-success), 0.06);
  border: 1px solid rgba(var(--v-theme-success), 0.18);
}
</style>
