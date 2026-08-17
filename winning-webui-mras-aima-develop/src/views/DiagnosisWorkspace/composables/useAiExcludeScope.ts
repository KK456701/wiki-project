/**
 * AI 生成 SQL——「选择排除对象」作用域选择逻辑
 *
 * 数据源对齐诊断案例：
 * - 按患者排除：从冻结的分子/分母明细（GET /api/diagnosis/cases/{caseId}/details）中
 *   搜索并勾选疑似多算记录，本地按关键词过滤。
 * - 按科室排除：从数据初筛（GET /api/diagnosis/cases/{caseId}/data-screening）的
 *   departmentOptions 中选择科室。
 *
 * 产出 scopeTargets（AiScopeTarget[]），随 IMPLEMENTER_SQL_REQUIREMENT 证据提交。
 */
import { computed, ref } from 'vue';
import { getDiagnosisDataScreening, searchDiagnosisPatientCandidates } from '@/services/diagnosis';
import { PATIENT_CLARIFICATION_DIRECTION, PATIENT_LOOKUP_MODE } from '@/constants/diagnosis';
import { isLikelyEncounterId } from '@/views/DiagnosisWorkspace/patient-search';
import { useDiagnosisStore } from '@/stores/diagnosis';
import type {
  AiPatientOption,
  AiScopeTarget,
  DiagnosisDataScreening,
} from '@/types/diagnosis';

export type AiScopeMode = 'PATIENT' | 'DEPARTMENT';

export function useAiExcludeScope(getCaseId: () => string | null) {
  const diagnosis = useDiagnosisStore();
  const aiScopeMode = ref<AiScopeMode>('PATIENT');

  // 按患者排除
  const aiPatientSearch = ref('');
  const aiPatientPool = ref<AiPatientOption[]>([]);
  const aiSelectedPatients = ref<AiPatientOption[]>([]);

  // 按科室排除
  const aiDepartmentSearch = ref('');
  const aiSelectedDepartments = ref<string[]>([]);
  const departmentOptions = ref<DiagnosisDataScreening['departmentOptions']>([]);

  const loading = ref(false);
  const error = ref('');

  const filteredPatientOptions = computed<AiPatientOption[]>(() => {
    const keyword = aiPatientSearch.value.trim().toLowerCase();
    const pool = aiPatientPool.value;
    return keyword
      ? pool.filter((item) => `${item.label} ${item.value}`.toLowerCase().includes(keyword))
      : pool;
  });

  const filteredDepartments = computed(() => {
    const keyword = aiDepartmentSearch.value.trim().toLowerCase();
    const values = departmentOptions.value;
    return keyword
      ? values.filter((item) => `${item.label} ${item.value}`.toLowerCase().includes(keyword))
      : values;
  });

  const scopeTargets = computed<AiScopeTarget[]>(() => {
    if (aiScopeMode.value === 'PATIENT' && aiSelectedPatients.value.length) {
      const grouped = new Map<string, AiPatientOption[]>();
      for (const item of aiSelectedPatients.value) {
        const list = grouped.get(item.field) ?? [];
        list.push(item);
        grouped.set(item.field, list);
      }
      return [...grouped.entries()].map(([field, items]) => ({
        targetType: 'RECORD',
        field,
        values: items.map((item) => item.value),
        labels: items.map((item) => item.label),
      }));
    }
    if (aiScopeMode.value === 'DEPARTMENT' && aiSelectedDepartments.value.length) {
      return aiSelectedDepartments.value.map((value) => {
        const option = departmentOptions.value.find((item) => item.value === value);
        return {
          targetType: 'DEPARTMENT',
          field: option?.field ?? 'CURRENT_DEPT_NAME',
          values: [value],
          labels: [option?.label ?? value],
        };
      });
    }
    return [];
  });

  const summaryText = computed(() =>
    aiScopeMode.value === 'PATIENT'
      ? `目前按患者排除 · 已选 ${aiSelectedPatients.value.length} 位`
      : `目前按科室排除 · 已选 ${aiSelectedDepartments.value.length} 个`,
  );

  /** 加载数据初筛的科室选项（仅在存在 caseId 时可用） */
  async function loadDepartmentOptions() {
    const cid = getCaseId();
    if (!cid) return;
    loading.value = true;
    error.value = '';
    try {
      const screening = await getDiagnosisDataScreening(cid);
      departmentOptions.value = screening.departmentOptions ?? [];
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '可选科室加载失败。';
      departmentOptions.value = [];
    } finally {
      loading.value = false;
    }
  }

  /** 搜索患者：使用完整患者候选链路，不再受分子/分母前 50 条限制。 */
  async function searchPatients() {
    const cid = getCaseId();
    if (!cid) return;
    loading.value = true;
    error.value = '';
    try {
      const keyword = aiPatientSearch.value.trim();
      const lookupMode = isLikelyEncounterId(keyword)
        ? PATIENT_LOOKUP_MODE.ENCOUNTER_ID
        : PATIENT_LOOKUP_MODE.NAME_BED;
      const criteria = !keyword
        ? {}
        : lookupMode === PATIENT_LOOKUP_MODE.ENCOUNTER_ID
          ? { encounterId: keyword }
          : /^\d+$/.test(keyword)
            ? { bedNo: keyword }
            : { fullName: keyword };
      const result = await searchDiagnosisPatientCandidates(cid, {
        direction: PATIENT_CLARIFICATION_DIRECTION.OVER_COUNTED,
        lookupMode,
        ...criteria,
        page: 1,
        pageSize: 50,
      });
      aiPatientPool.value = result.items.map((item) => ({
        value: item.encounterId,
        field: 'ENCOUNTER_ID',
        label: `${[item.fullName || item.encounterId, item.departmentName]
          .filter(Boolean)
          .join(' · ')}（${item.encounterId}）`,
      }));
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '可选患者加载失败。';
      aiPatientPool.value = [];
    } finally {
      loading.value = false;
    }
  }

  function togglePatient(option: AiPatientOption) {
    aiScopeMode.value = 'PATIENT';
    aiSelectedDepartments.value = [];
    const selected = aiSelectedPatients.value.some((item) => item.value === option.value);
    aiSelectedPatients.value = selected
      ? aiSelectedPatients.value.filter((item) => item.value !== option.value)
      : [...aiSelectedPatients.value, option];
  }

  function toggleDepartment(value: string) {
    aiScopeMode.value = 'DEPARTMENT';
    aiSelectedPatients.value = [];
    const selected = aiSelectedDepartments.value.includes(value);
    aiSelectedDepartments.value = selected
      ? aiSelectedDepartments.value.filter((item) => item !== value)
      : [...aiSelectedDepartments.value, value];
  }

  function clearScope() {
    aiSelectedPatients.value = [];
    aiSelectedDepartments.value = [];
  }

  /**
   * 带入数据确认：把第 2 步数据确认固化的「数据多了」患者 / 科室一键带入 AI 排除范围。
   * 对齐 readonly importConfirmationScope() 行为。
   */
  function importConfirmationScope() {
    const cid = getCaseId();
    if (!cid) return;
    const confirmation = diagnosis.getCase(cid)?.dataConfirmation;
    if (!confirmation) return;

    // 公共规则优先（一旦选中公共规则，不再按明细带入）
    if (Array.isArray(confirmation.publicRuleIds) && confirmation.publicRuleIds.length) {
      return;
    }

    const rows = confirmation.overIncludedRows ?? [];
    if (rows.length) {
      aiScopeMode.value = 'PATIENT';
      aiSelectedDepartments.value = [];
      const patientMap = new Map(aiSelectedPatients.value.map((item) => [item.value, item]));
      for (const row of rows) {
        if (!row.recordId) continue;
        patientMap.set(row.recordId, {
          value: row.recordId,
          field: row.rowKey.split(':')[0] || 'ENCOUNTER_ID',
          label: `${row.label}（${row.recordId}）`,
        });
      }
      aiSelectedPatients.value = [...patientMap.values()];
      return;
    }

    const departments = confirmation.overIncludedDepartments ?? [];
    if (departments.length) {
      aiScopeMode.value = 'DEPARTMENT';
      aiSelectedPatients.value = [];
      const values = departments.flatMap((d) => d.values ?? []);
      aiSelectedDepartments.value = [...new Set(values)];
    }
  }

  return {
    aiScopeMode,
    aiPatientSearch,
    filteredPatientOptions,
    aiSelectedPatients,
    aiDepartmentSearch,
    aiSelectedDepartments,
    departmentOptions,
    filteredDepartments,
    scopeTargets,
    summaryText,
    loading,
    error,
    loadDepartmentOptions,
    searchPatients,
    togglePatient,
    toggleDepartment,
    clearScope,
    importConfirmationScope,
  };
}
