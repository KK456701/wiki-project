/**
 * 数据确认步骤——患者勾选、科室排除、澄清提交、进入链路核查与确认无异议逻辑
 *
 * 流程对齐 readonly 参考实现（StandardDiagnosisWorkspace.vue）：
 * - 「要求澄清」→ 按方向（OVER_INCLUDED / UNDER_INCLUDED）分别调用 CLARIFY_DATA_CONFIRMATION，
 *   不推进步骤，结果由后端回流到 snapshot.dataConfirmation.clarifications。
 * - 「进入链路核查」→ 先 SUBMIT_DATA_CONFIRMATION（confirmedNoIssue = 有无问题取反），再跳转 lineage。
 * - 「确认无异议」→ SUBMIT_DATA_CONFIRMATION(confirmedNoIssue=true) → CLOSE_AS_CORRECT。
 */
import { ref } from 'vue';
import { useDiagnosisStore } from '@/stores/diagnosis';
import {
  DATA_CLARIFICATION_DIRECTION,
  DIAGNOSIS_ACTION,
  PATIENT_CLARIFICATION_DIRECTION,
} from '@/constants/diagnosis';
import { getDiagnosisDataScreening } from '@/services/diagnosis';
import type {
  DiagnosisDataScreening,
  DiagnosisDetailRow,
  PatientClarificationDirection,
} from '@/types/diagnosis';

/** 科室排除选项（来自数据初筛 departmentOptions） */
type DepartmentOption = DiagnosisDataScreening['departmentOptions'][number];

/** 选中患者 → 后端 overIncludedRows 元素：{ rowKey, recordId, label } */
interface ConfirmationRowItem {
  rowKey: string;
  recordId: string;
  label: string;
}

interface ClarificationRequestOptions {
  requestId?: string;
  signal?: AbortSignal;
  conversationId?: string;
}

/**
 * 从明细行中启发式识别列语义，将原始行映射为后端 SUBMIT_DATA_CONFIRMATION /
 * CLARIFY_DATA_CONFIRMATION 所需的记录标识格式。
 *
 * 列语义识别逻辑与 useDetailSelection 保持一致，不额外引入依赖。
 */
export function mapRowToConfirmationItem(row: DiagnosisDetailRow): ConfirmationRowItem {
  const keys = Object.keys(row);
  const lowerKeys = keys.map((k) => k.toLowerCase());

  function match(patterns: RegExp[]): string | null {
    for (const p of patterns) {
      const idx = lowerKeys.findIndex((k) => p.test(k));
      if (idx >= 0) return keys[idx];
    }
    return null;
  }

  const encounterKey = match([
    /^encounter_id$|^encounterid$|^就诊号$|^住院号$/i,
    /admission.*id|inhospital.*id|visit.*id/i,
    /encounter|就诊号|住院号|enc/i,
  ]);
  const nameKey = match([/name|姓名|患者|patient_name|patientname/i]);
  const deptKey = match([
    /dept_name|deptname|department_name|科室名称|当前科室$/i,
    /dept|科室|department/i,
  ]);

  const recordId = encounterKey ? String(row[encounterKey] ?? '') : '';
  const labelParts: string[] = [];
  if (nameKey) labelParts.push(String(row[nameKey] ?? ''));
  if (deptKey) labelParts.push(String(row[deptKey] ?? ''));
  const label = labelParts.join(' · ') || recordId || '未知患者';

  return {
    rowKey: recordId ? `ENCOUNTER_ID:${recordId}` : '',
    recordId,
    label,
  };
}

/** 将选中患者聚合成 RECORD 类 targets（CLARIFY_DATA_CONFIRMATION 用） */
export function buildRecordTargets(rows: DiagnosisDetailRow[]) {
  const items = rows.map(mapRowToConfirmationItem);
  const byField = new Map<string, { values: string[]; labels: string[] }>();
  for (const item of items) {
    const field = item.rowKey.split(':')[0] || 'ENCOUNTER_ID';
    const group = byField.get(field) ?? { values: [], labels: [] };
    group.values.push(item.recordId);
    group.labels.push(item.label);
    byField.set(field, group);
  }
  return [...byField.entries()].map(([field, group]) => ({
    targetType: 'RECORD',
    field,
    values: group.values,
    labels: group.labels,
  }));
}

/** 将选中科室转成 DEPARTMENT 类 targets */
export function buildDepartmentTargets(values: string[], options: DepartmentOption[]) {
  return values.map((value) => {
    const option = options.find((item) => item.value === value);
    return {
      targetType: 'DEPARTMENT',
      field: option?.field ?? 'CURRENT_DEPT_NAME',
      values: [value],
      labels: [option?.label ?? value],
    };
  });
}

/** 澄清/提交所需的完整输入 */
export interface DataConfirmationSubmitInput {
  overIncludedRows: DiagnosisDetailRow[];
  overIncludedNote: string;
  underIncludedNote: string;
  selectedDepartments: string[];
  departmentOptions: DepartmentOption[];
}

export function useDataConfirmation(caseId: string) {
  const diagnosis = useDiagnosisStore();

  /** 跨页 / 跨分组选中的患者标识集合 */
  const selectedKeys = ref<Set<string>>(new Set());
  /** 当前已选中的患者行数据 */
  const selectedRows = ref<DiagnosisDetailRow[]>([]);
  /** 「数据多了」方向选中的排除科室 */
  const selectedDepartments = ref<string[]>([]);
  /** 科室排除选项（来自数据初筛 departmentOptions） */
  const departmentOptions = ref<DepartmentOption[]>([]);
  const departmentsLoading = ref(false);

  /** 澄清提交中（CLARIFY_DATA_CONFIRMATION） */
  const clarifying = ref(false);
  /** 进入链路核查提交中（SUBMIT_DATA_CONFIRMATION） */
  const submitting = ref(false);
  /** 确认无异议提交中 */
  const finishing = ref(false);
  /** 提交错误 */
  const submitError = ref('');

  /** 清空「数据多了」方向所有已选对象（患者 + 科室） */
  function clearSelection() {
    selectedKeys.value = new Set();
    selectedRows.value = [];
    selectedDepartments.value = [];
  }

  function clearDepartments() {
    selectedDepartments.value = [];
  }

  /**
   * 「数据多了」方向更新已选患者——与科室互斥：
   * 存在选中患者时自动清空已选科室（患者、科室二选一）。
   */
  function selectPatients(keys: Set<string>) {
    selectedKeys.value = keys;
    if (keys.size > 0) selectedDepartments.value = [];
  }

  /**
   * 「数据多了」方向更新已选科室——与患者互斥：
   * 存在选中科室时自动清空已选患者（患者、科室二选一）。
   */
  function selectDepartments(values: string[]) {
    selectedDepartments.value = values;
    if (values.length > 0) {
      selectedKeys.value = new Set();
      selectedRows.value = [];
    }
  }

  /** 加载数据初筛的科室选项（供「数据多了」按科室排除使用） */
  async function loadDepartmentOptions() {
    if (departmentsLoading.value) return;
    departmentsLoading.value = true;
    try {
      const screening = await getDiagnosisDataScreening(caseId);
      departmentOptions.value = screening.departmentOptions ?? [];
    } catch {
      departmentOptions.value = [];
    } finally {
      departmentsLoading.value = false;
    }
  }

  /** 是否有需要澄清的问题（数据多了 / 数据少了任一存在） */
  function hasIssueOf(input: DataConfirmationSubmitInput): boolean {
    return (
      input.overIncludedRows.length > 0 ||
      input.selectedDepartments.length > 0 ||
      !!input.overIncludedNote.trim() ||
      !!input.underIncludedNote.trim()
    );
  }

  /**
   * 要求澄清：按方向分别调用 CLARIFY_DATA_CONFIRMATION，不推进步骤。
   * 与 readonly 的 clarifyData() 行为一致。
   */
  async function clarify(
    input: DataConfirmationSubmitInput,
    userMessage = '',
    options: ClarificationRequestOptions = {},
  ): Promise<boolean> {
    const hasOver =
      input.overIncludedRows.length > 0 ||
      input.selectedDepartments.length > 0 ||
      !!input.overIncludedNote.trim();
    const hasUnder = !!input.underIncludedNote.trim();
    if (!hasOver && !hasUnder) return false;

    clarifying.value = true;
    submitError.value = '';
    try {
      if (hasOver) {
        await diagnosis.submitAction(
          caseId,
          DIAGNOSIS_ACTION.CLARIFY_DATA_CONFIRMATION,
          {
            direction: 'OVER_INCLUDED',
            targets: [
              ...buildRecordTargets(input.overIncludedRows),
              ...buildDepartmentTargets(input.selectedDepartments, input.departmentOptions),
            ],
            description: input.overIncludedNote.trim(),
            userMessage: userMessage.trim(),
            requestId: options.requestId,
          },
          { signal: options.signal },
        );
      }
      if (hasUnder) {
        await diagnosis.submitAction(
          caseId,
          DIAGNOSIS_ACTION.CLARIFY_DATA_CONFIRMATION,
          {
            direction: 'UNDER_INCLUDED',
            targets: [],
            description: input.underIncludedNote.trim(),
            requestId: options.requestId,
          },
          { signal: options.signal },
        );
      }
      clearSelection();
      return true;
    } catch (e) {
      if ((e as Error)?.name === 'AbortError') return false;
      submitError.value = e instanceof Error ? e.message : '提交澄清失败';
      return false;
    } finally {
      clarifying.value = false;
    }
  }

  /**
   * 进入数据链路核查：先 SUBMIT_DATA_CONFIRMATION 固化数据确认，成功后由调用方跳转 lineage。
   * 与 readonly 的 proceedToLineage() 行为一致。
   */
  async function proceedToLineage(input: DataConfirmationSubmitInput): Promise<boolean> {
    submitting.value = true;
    submitError.value = '';
    try {
      await diagnosis.submitAction(caseId, DIAGNOSIS_ACTION.SUBMIT_DATA_CONFIRMATION, {
        confirmedNoIssue: !hasIssueOf(input),
        overIncludedRows: input.overIncludedRows.map(mapRowToConfirmationItem),
        overIncludedNote: input.overIncludedNote.trim(),
        overIncludedDepartments: buildDepartmentTargets(
          input.selectedDepartments,
          input.departmentOptions,
        ),
        underIncludedNote: input.underIncludedNote.trim(),
        underIncludedTargets: [],
        publicRuleIds: [],
      });
      clearSelection();
      return true;
    } catch (e) {
      submitError.value = e instanceof Error ? e.message : '提交数据确认失败';
      return false;
    } finally {
      submitting.value = false;
    }
  }

  async function clarifyPatient(
    row: DiagnosisDetailRow,
    userMessage = '',
    options: ClarificationRequestOptions = {},
    direction: PatientClarificationDirection = PATIENT_CLARIFICATION_DIRECTION.OVER_COUNTED,
  ): Promise<boolean> {
    clarifying.value = true;
    submitError.value = '';
    try {
      await diagnosis.submitAction(
        caseId,
        DIAGNOSIS_ACTION.CLARIFY_DATA_CONFIRMATION,
        {
          direction:
            direction === PATIENT_CLARIFICATION_DIRECTION.UNDER_COUNTED
              ? DATA_CLARIFICATION_DIRECTION.UNDER_INCLUDED
              : DATA_CLARIFICATION_DIRECTION.OVER_INCLUDED,
          targets: buildRecordTargets([row]),
          description: '',
          userMessage: userMessage.trim(),
          requestId: options.requestId,
          conversationId: options.conversationId,
        },
        { signal: options.signal },
      );
      return true;
    } catch (e) {
      if ((e as Error)?.name === 'AbortError') return false;
      submitError.value = e instanceof Error ? e.message : '提交澄清失败';
      return false;
    } finally {
      clarifying.value = false;
    }
  }

  async function cancelPatientClarification(requestId: string): Promise<boolean> {
    try {
      await diagnosis.submitAction(caseId, DIAGNOSIS_ACTION.CANCEL_DATA_CLARIFICATION, {
        requestId,
      });
      return true;
    } catch (e) {
      submitError.value = e instanceof Error ? e.message : '停止患者澄清失败';
      return false;
    }
  }

  async function proceedWithPatient(row: DiagnosisDetailRow): Promise<boolean> {
    return proceedToLineage({
      overIncludedRows: [row],
      overIncludedNote: '',
      underIncludedNote: '',
      selectedDepartments: [],
      departmentOptions: departmentOptions.value,
    });
  }

  async function proceedWithDepartment(value: string): Promise<boolean> {
    return proceedToLineage({
      overIncludedRows: [],
      overIncludedNote: '',
      underIncludedNote: '',
      selectedDepartments: [value],
      departmentOptions: departmentOptions.value,
    });
  }

  /**
   * 确认无异议：先提交 confirmedNoIssue 的数据确认，再关闭案例结束排查。
   * 与 readonly 的 finishAsCorrect 行为一致：
   * SUBMIT_DATA_CONFIRMATION(confirmedNoIssue=true) → CLOSE_AS_CORRECT
   */
  async function finishAsCorrect(): Promise<boolean> {
    finishing.value = true;
    submitError.value = '';
    try {
      await diagnosis.submitAction(caseId, DIAGNOSIS_ACTION.SUBMIT_DATA_CONFIRMATION, {
        confirmedNoIssue: true,
        overIncludedRows: [],
        overIncludedNote: '',
        overIncludedDepartments: [],
        underIncludedNote: '',
        underIncludedTargets: [],
        publicRuleIds: [],
      });
      await diagnosis.submitAction(caseId, DIAGNOSIS_ACTION.CLOSE_AS_CORRECT, {
        conclusion: '实施人员已核对本次分子、分母明细，确认当前结果无异议。',
      });
      return true;
    } catch (e) {
      submitError.value = e instanceof Error ? e.message : '确认无异议失败';
      return false;
    } finally {
      finishing.value = false;
    }
  }

  return {
    selectedKeys,
    selectedRows,
    selectedDepartments,
    departmentOptions,
    departmentsLoading,
    clarifying,
    submitting,
    submitError,
    finishing,
    clearSelection,
    clearDepartments,
    selectPatients,
    selectDepartments,
    loadDepartmentOptions,
    clarify,
    proceedToLineage,
    clarifyPatient,
    cancelPatientClarification,
    proceedWithPatient,
    proceedWithDepartment,
    finishAsCorrect,
  };
}
