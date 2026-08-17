/**
 * AI 生成 SQL——数据确认内容带入上下文逻辑
 *
 * 把第 2 步「数据确认」固化的「数据多了 / 数据少了」内容，翻译成第 3 步
 * AI 生成 SQL 输入框的自然语言要求（requirement）。
 *
 * 分工对齐 readonly 参考实现（clarificationRequirement / importConfirmationScope）：
 * - 输入框（requirement）：带入「数据多了 / 数据少了」补充说明与公共规则文字；
 * - 选择排除对象（scopeTargets）：由 AiExcludeScopePicker 在挂载时调用
 *   useAiExcludeScope.importConfirmationScope 自动带入「数据多了」勾选的患者 / 科室，
 *   生成时再经 useDataFlowActions.requirementTextOf 单独拼成结构化排除说明。
 */
import { computed } from 'vue';
import { useDiagnosisStore } from '@/stores/diagnosis';
import type { DataConfirmation } from '@/types/diagnosis';

/** 公共规则 ID → 自然语言要求（对齐 readonly clarificationRequirement） */
const PUBLIC_RULE_REQUIREMENTS: Record<string, string> = {
  PUBLIC_001: '按公共规则排除患者姓名包含“测试”或“test”的数据',
  PUBLIC_002: '按公共规则排除当前科室名称包含“测试”“test”或“血液透析门诊”的数据',
  PUBLIC_003: '最终明细存在重复业务编号，请人工核对当前指标相关事件是否重复启用',
};

/**
 * 把数据确认内容翻译为 AI 生成 SQL 输入框的自然语言要求（对齐 readonly clarificationRequirement）。
 * 带入「数据多了」勾选的患者 / 科室标签、「数据多了/少了」说明与公共规则。
 */
export function clarificationRequirementOf(confirmation?: DataConfirmation): string {
  if (!confirmation) return '';

  const rows = confirmation.overIncludedRows ?? [];
  const departments = confirmation.overIncludedDepartments ?? [];
  const parts: string[] = [];

  const rowLabels = rows.map((item) => String(item.label || item.recordId || '')).filter(Boolean);
  if (rowLabels.length) parts.push(`排除这些疑似多算记录：${rowLabels.join('、')}`);

  const departmentLabels = departments.flatMap((item) =>
    Array.isArray(item.labels) ? item.labels.map(String) : [],
  );
  if (departmentLabels.length) parts.push(`核对并排除科室范围：${departmentLabels.join('、')}`);

  if (confirmation.overIncludedNote?.trim()) {
    parts.push(`数据多了：${confirmation.overIncludedNote.trim()}`);
  }
  if (confirmation.underIncludedNote?.trim()) {
    parts.push(`数据少了：${confirmation.underIncludedNote.trim()}`);
  }

  const publicRuleIds = (confirmation.publicRuleIds ?? []).map(String);
  for (const ruleId of publicRuleIds) {
    const requirement = PUBLIC_RULE_REQUIREMENTS[ruleId];
    if (requirement) parts.push(requirement);
  }

  return parts.join('；');
}

/**
 * 组合式函数：读取当前案例的数据确认内容，产出第 3 步 AI 生成 SQL 输入框的初始要求。
 */
export function useAiSqlContext(getCaseId: () => string | null) {
  const diagnosis = useDiagnosisStore();

  const confirmation = computed<DataConfirmation | undefined>(() => {
    const cid = getCaseId();
    return cid ? diagnosis.getCase(cid)?.dataConfirmation : undefined;
  });

  const initialRequirement = computed(() => clarificationRequirementOf(confirmation.value));

  return { confirmation, initialRequirement };
}
