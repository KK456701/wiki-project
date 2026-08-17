/**
 * 明细弹窗——患者筛选与勾选逻辑
 */
import { ref, toValue, watch, type MaybeRefOrGetter } from 'vue';
import type { DiagnosisDetailsResponse, DiagnosisDetailRow } from '@/types/diagnosis';
import type { DetailGroup } from '@/types/chat';

export function useDetailSelection(selectedKeys?: MaybeRefOrGetter<Set<string> | undefined>) {
  const filterName = ref('');
  const filterEncounter = ref('');
  const filterDept = ref('');

  // 本地勾选（跨分页累积）
  const localSelectedKeys = ref<Set<string>>(new Set());

  // 从外部同步勾选（支持父级清空 / 重置后正确回流到本地）
  watch(
    () => toValue(selectedKeys),
    (ext) => {
      localSelectedKeys.value = new Set(ext ?? []);
    },
    { immediate: true },
  );

  /** 按启发式规则识别列语义 */
  type ColumnSemantic = {
    nameKey: string | null;
    encounterKey: string | null;
    deptKey: string | null;
  };

  function deriveColumnSemantic(rows: DiagnosisDetailRow[]): ColumnSemantic {
    const keys = Object.keys(rows[0] ?? {});
    const lowerKeys = keys.map((k) => k.toLowerCase());
    function match(patterns: RegExp[]): string | null {
      for (const p of patterns) {
        const idx = lowerKeys.findIndex((k) => p.test(k));
        if (idx >= 0) return keys[idx];
      }
      return null;
    }
    return {
      nameKey: match([/name|姓名|患者|patient_name|patientname/i]),
      encounterKey: match([/encounter|就诊号|住院号|admission|inhospital|visit.*id|enc/i]),
      deptKey: match([/dept|科室|department|dept_name|deptname/i]),
    };
  }

  /** 科室下拉选项（从当前数据中提取唯一值） */
  function deriveDepartmentOptions(data: DiagnosisDetailsResponse, sem: ColumnSemantic): string[] {
    const deptKey = sem.deptKey;
    if (!deptKey || !data) return [];
    const seen = new Set<string>();
    for (const row of data.rows) {
      const v = String(row[deptKey] ?? '').trim();
      if (v) seen.add(v);
    }
    return Array.from(seen).sort((a, b) => a.localeCompare(b, 'zh-CN'));
  }

  /** 按当前筛选条件过滤行 */
  function filterRows(rows: DiagnosisDetailRow[], sem: ColumnSemantic): DiagnosisDetailRow[] {
    if (!filterName.value && !filterEncounter.value && !filterDept.value) return rows;
    const nameQ = filterName.value.trim().toLowerCase();
    const encQ = filterEncounter.value.trim().toLowerCase();
    const deptQ = filterDept.value.trim();
    return rows.filter((row) => {
      if (nameQ && sem.nameKey) {
        if (
          !String(row[sem.nameKey] ?? '')
            .toLowerCase()
            .includes(nameQ)
        )
          return false;
      }
      if (encQ && sem.encounterKey) {
        if (
          !String(row[sem.encounterKey] ?? '')
            .toLowerCase()
            .includes(encQ)
        )
          return false;
      }
      if (deptQ && sem.deptKey) {
        if (String(row[sem.deptKey] ?? '').trim() !== deptQ) return false;
      }
      return true;
    });
  }

  /** 当前行唯一标识 */
  function rowKey(
    row: DiagnosisDetailRow,
    group: DetailGroup,
    page: number,
    index: number,
  ): string {
    const sem = deriveColumnSemantic([row]);
    if (sem.encounterKey) {
      const v = row[sem.encounterKey];
      if (v !== undefined && v !== null && v !== '') return `${group}:${String(v)}`;
    }
    return `${group}:${page}:${index}`;
  }

  function isRowSelected(
    row: DiagnosisDetailRow,
    group: DetailGroup,
    page: number,
    index: number,
  ): boolean {
    return localSelectedKeys.value.has(rowKey(row, group, page, index));
  }

  function toggleRow(row: DiagnosisDetailRow, group: DetailGroup, page: number, index: number) {
    const key = rowKey(row, group, page, index);
    const next = new Set(localSelectedKeys.value);
    if (next.has(key)) next.delete(key);
    else next.add(key);
    localSelectedKeys.value = next;
  }

  function selectAllPage(rows: DiagnosisDetailRow[], group: DetailGroup, page: number) {
    const next = new Set(localSelectedKeys.value);
    for (let i = 0; i < rows.length; i++) {
      next.add(rowKey(rows[i], group, page, i));
    }
    localSelectedKeys.value = next;
  }

  function deselectAllPage(rows: DiagnosisDetailRow[], group: DetailGroup, page: number) {
    const next = new Set(localSelectedKeys.value);
    for (let i = 0; i < rows.length; i++) {
      next.delete(rowKey(rows[i], group, page, i));
    }
    localSelectedKeys.value = next;
  }

  /**
   * 从行集合收集选中的行数据
   * 近似实现：筛选当前页数据中匹配 selectedKeys 的行
   */
  function collectSelectedRows(
    allRows: DiagnosisDetailRow[],
    group: DetailGroup,
    page: number,
  ): DiagnosisDetailRow[] {
    return allRows.filter((row, i) => isRowSelected(row, group, page, i)).map((r) => ({ ...r }));
  }

  return {
    filterName,
    filterEncounter,
    filterDept,
    localSelectedKeys,
    deriveColumnSemantic,
    deriveDepartmentOptions,
    filterRows,
    rowKey,
    isRowSelected,
    toggleRow,
    selectAllPage,
    deselectAllPage,
    collectSelectedRows,
  };
}
