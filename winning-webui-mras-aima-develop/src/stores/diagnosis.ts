/**
 * 排查案例 Pinia Store
 *
 * 持有所有排查案例的快照，作为前端驱动排查 UI 的唯一真相来源。
 * 每次提交动作后整体替换本地快照（diagnosis-cases.md §5.2）。
 */
import { defineStore } from 'pinia';
import { ref } from 'vue';
import {
  createDiagnosisCase,
  getDiagnosisCase,
  submitDiagnosisAction,
  getDiagnosisDetails,
  DiagnosisActionError,
} from '@/services/diagnosis';
import {
  DIAGNOSIS_ACTION,
  DIAGNOSIS_STEP,
  GATE,
  type DiagnosisActionName,
} from '@/constants/diagnosis';
import type {
  DiagnosisCaseSnapshot,
  CreateDiagnosisCaseInput,
  DiagnosisDetailsResponse,
} from '@/types/diagnosis';
import type { DetailGroup } from '@/types/chat';

export const useDiagnosisStore = defineStore('diagnosis', () => {
  /** caseId → 快照 */
  const cases = ref<Record<string, DiagnosisCaseSnapshot>>({});
  /** 正在加载快照的 caseId 集合 */
  const loadingCaseIds = ref<Set<string>>(new Set());
  /** 正在提交动作的 caseId 集合 */
  const submittingCaseIds = ref<Set<string>>(new Set());
  /** 明细缓存：key = `${caseId}:${group}:${page}:${pageSize}` */
  const detailsCache = ref<Record<string, DiagnosisDetailsResponse>>({});

  function getCase(caseId: string): DiagnosisCaseSnapshot | undefined {
    return cases.value[caseId];
  }

  function isCaseLoading(caseId: string): boolean {
    return loadingCaseIds.value.has(caseId);
  }

  function isActionSubmitting(caseId: string): boolean {
    return submittingCaseIds.value.has(caseId);
  }

  /** 创建案例并写入本地快照 */
  async function createCase(input: CreateDiagnosisCaseInput): Promise<DiagnosisCaseSnapshot> {
    const snapshot = await createDiagnosisCase(input);
    cases.value = { ...cases.value, [snapshot.caseId]: snapshot };
    return snapshot;
  }

  /** 懒加载快照（历史恢复 / 卡片挂载时调用） */
  async function loadCase(caseId: string): Promise<DiagnosisCaseSnapshot> {
    loadingCaseIds.value = new Set(loadingCaseIds.value).add(caseId);
    try {
      const snapshot = await getDiagnosisCase(caseId);
      cases.value = { ...cases.value, [caseId]: snapshot };
      return snapshot;
    } finally {
      const next = new Set(loadingCaseIds.value);
      next.delete(caseId);
      loadingCaseIds.value = next;
    }
  }

  /** 提交动作，整体替换快照 */
  async function submitAction(
    caseId: string,
    action: DiagnosisActionName,
    payload: Record<string, unknown> = {},
    options: { timeout?: number; signal?: AbortSignal } = {},
  ): Promise<DiagnosisCaseSnapshot> {
    submittingCaseIds.value = new Set(submittingCaseIds.value).add(caseId);
    try {
      const snapshot = await submitDiagnosisAction(caseId, action, payload, options);
      cases.value = { ...cases.value, [caseId]: snapshot };
      // B3: 快照被替换（排查步骤推进 / 明细上下文变化）后，使该案例的明细缓存失效，避免陈旧数据
      invalidateDetailsCache(caseId);
      return snapshot;
    } catch (e) {
      // 步骤顺序冲突：本地快照过期（落后于后端真实步骤）所致。
      // 重新拉取最新快照对齐，并以友好文案替换后端内部步骤名后抛出，避免用户反复点击同一失效按钮。
      if (e instanceof DiagnosisActionError && e.code === 'DIAGNOSIS_STEP_ORDER_VIOLATION') {
        let resynced = false;
        try {
          const latest = await getDiagnosisCase(caseId);
          cases.value = { ...cases.value, [caseId]: latest };
          invalidateDetailsCache(caseId);
          resynced = true;
        } catch {
          // 重同步失败则保留原始错误，由上层按原 message 展示
        }
        if (resynced) {
          throw new DiagnosisActionError('排查进度已与服务器同步，请重新操作', e.code);
        }
      }
      throw e;
    } finally {
      const next = new Set(submittingCaseIds.value);
      next.delete(caseId);
      submittingCaseIds.value = next;
    }
  }

  /** 清除某案例的全部明细缓存（键前缀 `${caseId}:`） */
  function invalidateDetailsCache(caseId: string) {
    const prefix = `${caseId}:`;
    const next: Record<string, DiagnosisDetailsResponse> = {};
    for (const key of Object.keys(detailsCache.value)) {
      if (!key.startsWith(prefix)) next[key] = detailsCache.value[key];
    }
    detailsCache.value = next;
  }

  /** 加载分子/分母明细（带缓存） */
  async function loadDetails(
    caseId: string,
    group: DetailGroup | undefined,
    page = 1,
    pageSize = 50,
  ): Promise<DiagnosisDetailsResponse> {
    const key = `${caseId}:${group ?? 'default'}:${page}:${pageSize}`;
    const data = await getDiagnosisDetails(caseId, group, page, pageSize);
    detailsCache.value = { ...detailsCache.value, [key]: data };
    return data;
  }

  /** 由当前步骤推导所处关卡编号（1/2/3），非关卡步骤返回 0 */
  function currentGateNumber(step: string): number {
    if (step === DIAGNOSIS_STEP.GATE_1_SCHEMA) return 1;
    if (step === DIAGNOSIS_STEP.GATE_2_EVENT) return 2;
    if (step === DIAGNOSIS_STEP.GATE_3_VALUE) return 3;
    return 0;
  }

  /**
   * 自动连跑三关校验（前端交互流程文档 §4.3 advanceDiagnosisGates，对齐 readonly）。
   *
   * 在 CONFIRM_CALIBER / RUN_BASE_CHECKS / RECHECK_GATE 之后调用。
   * 健壮性要点（修复 A2）：
   * 1. 若后端在确认口径后未把 currentStep 推进到 GATE_*（仍为 CALIBER_CONFIRMATION 等），
   *    先显式发一次 RUN_GATE {gate:1} 作为起点，避免「第一轮就 return、三关永不跑」。
   * 2. 每轮读完 currentStep 后发对应 RUN_GATE；用「currentStep 实际推进」作为连跑推进信号。
   * 3. 任一圈未推进（next <= gate）或到非关卡步骤即安全退出，杜绝无限循环
   *    （readonly 的循环无上限、无变化校验，存在无限重跑同一关隐患，此处已规避）。
   */
  async function advanceGates(caseId: string): Promise<DiagnosisCaseSnapshot | undefined> {
    let current = getCase(caseId);
    if (!current) return current;

    // 1. 起点兜底：仅初次运行（尚无任何关卡结果）且步骤未推进到 GATE_* 时显式启动第 1 关。
    //    已有关卡结果但步骤已离开关卡，说明三关已跑完，直接退出，避免误发 RUN_GATE。
    if (currentGateNumber(current.currentStep) === 0) {
      if (current.gateResults.length > 0) return current;
      current = await submitAction(caseId, DIAGNOSIS_ACTION.RUN_GATE, { gate: GATE.SCHEMA });
      if (currentGateNumber(current.currentStep) === 0) return current; // 后端仍未推进/拒绝，安全退出
    }

    // 2. 自动连跑三关
    let gate = currentGateNumber(current.currentStep);
    for (let guard = 0; guard < 6; guard++) {
      const existing = current.gateResults.find((g) => Number(g.gate) === gate);
      if (existing && String(existing.status) === 'BLOCKED') return current;

      current = await submitAction(caseId, DIAGNOSIS_ACTION.RUN_GATE, { gate });
      const result = current.gateResults.find((g) => Number(g.gate) === gate);
      if (!result || String(result.status) === 'BLOCKED') return current;

      const next = currentGateNumber(current.currentStep);
      if (next === 0) return current; // 三关全部跑完，自然结束
      if (next <= gate) return current; // 未推进或回退，防无限循环
      gate = next;
    }
    return current;
  }

  /**
   * 分子/分母明细入口是否可用：第 2 关（事件配置校验）PASS 且快照已冻结 overviewSqlHash。
   * overviewSqlHash 由后端在口径确认（CONFIRM_CALIBER）阶段写入 caseExpectedClassification，
   * 缺它时后端明细查询返回 409 DIAGNOSIS_DETAIL_CONTEXT_MISSING（cases.md §3）。
   */
  function isDetailAvailable(caseId: string): boolean {
    const s = cases.value[caseId];
    if (!s) return false;
    const g2 = s.gateResults.find((g) => Number(g.gate) === 2);
    if (!g2 || String(g2.status) !== 'PASSED') return false;
    const hash = (s.caseExpectedClassification as Record<string, unknown> | undefined)
      ?.overviewSqlHash;
    return typeof hash === 'string' && hash.length > 0;
  }

  /** 明细入口不可用原因（D4：字段缺失时给出精准提示，而非静默 disabled） */
  function detailUnavailableReason(caseId: string): string {
    const s = cases.value[caseId];
    if (!s) return '排查任务未加载';
    const g2 = s.gateResults.find((g) => Number(g.gate) === 2);
    if (!g2 || String(g2.status) !== 'PASSED') {
      return '需第 2 关（事件配置校验）通过后，方可查看明细';
    }
    const hash = (s.caseExpectedClassification as Record<string, unknown> | undefined)
      ?.overviewSqlHash;
    if (typeof hash !== 'string' || hash.length === 0) {
      return '口径尚未冻结，暂无法查看明细';
    }
    return '';
  }

  return {
    cases,
    loadingCaseIds,
    submittingCaseIds,
    detailsCache,
    getCase,
    isCaseLoading,
    isActionSubmitting,
    createCase,
    loadCase,
    submitAction,
    loadDetails,
    advanceGates,
    isDetailAvailable,
    detailUnavailableReason,
  };
});
