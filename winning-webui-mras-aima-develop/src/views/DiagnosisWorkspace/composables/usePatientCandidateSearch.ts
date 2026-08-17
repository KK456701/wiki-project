import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { PATIENT_CLARIFICATION_DIRECTION, PATIENT_LOOKUP_MODE } from '@/constants/diagnosis';
import { searchDiagnosisPatientCandidates } from '@/services/diagnosis';
import { isLikelyEncounterId } from '@/views/DiagnosisWorkspace/patient-search';
import type {
  PatientCandidate,
  PatientClarificationDirection,
  PatientLookupMode,
  PatientCandidateSearchInput,
} from '@/types/diagnosis';

const PAGE_SIZE = 50;
const SEARCH_DEBOUNCE_MS = 300;
const ADMISSION_DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;
const NUMERIC_ID_PATTERN = /^\d+$/;

function patientCandidateInputError(lookupMode: PatientLookupMode, keyword: string): string {
  const value = keyword.trim();
  if (!value) return '';
  if (lookupMode === PATIENT_LOOKUP_MODE.NAME_BED && isLikelyEncounterId(value)) {
    return '这看起来是患者就诊 ID，请切换到“患者就诊 ID”查询方式';
  }
  if (
    (lookupMode === PATIENT_LOOKUP_MODE.NAME_BED || lookupMode === PATIENT_LOOKUP_MODE.NAME_IMRN) &&
    !NUMERIC_ID_PATTERN.test(value) &&
    value.length < 2
  ) {
    return '姓名相关查询至少输入两个字符';
  }
  return '';
}

export function patientCandidateCriteria(
  lookupMode: PatientLookupMode,
  keyword: string,
): Partial<PatientCandidateSearchInput> {
  const value = keyword.trim();
  if (!value) return {};
  switch (lookupMode) {
    case PATIENT_LOOKUP_MODE.ENCOUNTER_ID:
      return { encounterId: value };
    case PATIENT_LOOKUP_MODE.IMRN_ADMISSION_DATE:
      return ADMISSION_DATE_PATTERN.test(value) ? { admissionDate: value } : { imrn: value };
    case PATIENT_LOOKUP_MODE.NAME_IMRN:
      return NUMERIC_ID_PATTERN.test(value) ? { imrn: value } : { fullName: value };
    default:
      return NUMERIC_ID_PATTERN.test(value) ? { bedNo: value } : { fullName: value };
  }
}

export function usePatientCandidateSearch(caseId: string) {
  const direction = ref<PatientClarificationDirection>(
    PATIENT_CLARIFICATION_DIRECTION.OVER_COUNTED,
  );
  const lookupMode = ref<PatientLookupMode>(PATIENT_LOOKUP_MODE.NAME_BED);
  const keyword = ref('');
  const items = ref<PatientCandidate[]>([]);
  const total = ref(0);
  const truncated = ref(false);
  const loading = ref(false);
  const error = ref('');
  const emptyReason = ref('');
  const warning = ref('');
  let requestSequence = 0;
  let debounceTimer: ReturnType<typeof setTimeout> | undefined;

  const inputError = computed(() => patientCandidateInputError(lookupMode.value, keyword.value));
  const keywordValid = computed(() => !inputError.value);

  const hint = computed(() => {
    if (inputError.value) return inputError.value;
    if (total.value > PAGE_SIZE) return `共 ${total.value} 条，继续输入可缩小范围`;
    if (total.value > 0) return `共 ${total.value} 条`;
    return direction.value === PATIENT_CLARIFICATION_DIRECTION.OVER_COUNTED
      ? '下拉列表来自当前完整分子、分母明细'
      : '下拉列表来自当前完整抽取结果；未命中时可继续精确搜索业务库';
  });

  function clearResultMessages() {
    error.value = '';
    emptyReason.value = '';
    warning.value = '';
  }

  async function search() {
    if (!keywordValid.value) {
      items.value = [];
      total.value = 0;
      return;
    }
    const sequence = ++requestSequence;
    loading.value = true;
    clearResultMessages();
    try {
      const result = await searchDiagnosisPatientCandidates(caseId, {
        direction: direction.value,
        lookupMode: lookupMode.value,
        ...patientCandidateCriteria(lookupMode.value, keyword.value),
        page: 1,
        pageSize: PAGE_SIZE,
      });
      if (sequence !== requestSequence) return;
      items.value = result.items;
      total.value = result.total;
      truncated.value = result.truncated;
      emptyReason.value = result.emptyReason;
      warning.value = result.warning;
    } catch (reason) {
      if (sequence !== requestSequence) return;
      error.value = reason instanceof Error ? reason.message : '患者查询失败';
      items.value = [];
      total.value = 0;
    } finally {
      if (sequence === requestSequence) loading.value = false;
    }
  }

  function scheduleSearch(value: string) {
    keyword.value = value;
    if (debounceTimer) clearTimeout(debounceTimer);
    debounceTimer = setTimeout(() => void search(), value.trim() ? SEARCH_DEBOUNCE_MS : 0);
  }

  function resetAndSearch() {
    keyword.value = '';
    items.value = [];
    total.value = 0;
    clearResultMessages();
    if (debounceTimer) clearTimeout(debounceTimer);
    void search();
  }

  function setDirection(value: PatientClarificationDirection) {
    direction.value = value;
    resetAndSearch();
  }

  function setLookupMode(value: PatientLookupMode) {
    lookupMode.value = value;
    resetAndSearch();
  }

  onMounted(() => void search());
  onBeforeUnmount(() => {
    requestSequence += 1;
    if (debounceTimer) clearTimeout(debounceTimer);
  });

  return {
    direction,
    lookupMode,
    keyword,
    items,
    total,
    truncated,
    loading,
    error,
    emptyReason,
    warning,
    keywordValid,
    hint,
    setDirection,
    setLookupMode,
    scheduleSearch,
    search,
  };
}
