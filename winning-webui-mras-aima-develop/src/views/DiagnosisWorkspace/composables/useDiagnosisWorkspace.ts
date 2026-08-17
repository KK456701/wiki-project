/**
 * 排查工作区编排逻辑（组合式函数）
 *
 * 持有前端三步状态（selection / data / lineage）、caseId 与快照，
 * 负责 URL 同步（刷新后可恢复步骤与 caseId）、建案（创建 → 确认口径 → 连跑三关）、
 * 以及关闭返回聊天。UI 组件只消费此处暴露的状态与回调，保持轻量。
 */
import { computed, onUnmounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useDiagnosisStore } from '@/stores/diagnosis';
import { useChatStore } from '@/stores/chat';
import { getRulesList, getRuleProfiles, type RuleItem } from '@/services/chat';
import type { RuleProfile } from '@/types/chat';
import { DIAGNOSIS_ACTION, DIAGNOSIS_STEP, GATE } from '@/constants/diagnosis';
import type { DiagnosisCaseSnapshot } from '@/types/diagnosis';

export type WorkspaceStep = 'selection' | 'data' | 'lineage';

export const WORKSPACE_STEPS: WorkspaceStep[] = ['selection', 'data', 'lineage'];

export interface CreateCaseInput {
  ruleId: string;
  profileId: string;
  statStart: string;
  statEnd: string;
}

export interface SelectionPrefill {
  ruleId?: string;
  profileId?: string;
  statStart?: string;
  statEnd?: string;
}

function isWorkspaceStep(v: unknown): v is WorkspaceStep {
  return v === 'selection' || v === 'data' || v === 'lineage';
}

export function useDiagnosisWorkspace() {
  const route = useRoute();
  const router = useRouter();
  const diagnosis = useDiagnosisStore();
  const chat = useChatStore();

  const step = ref<WorkspaceStep>(
    isWorkspaceStep(route.query.step) ? (route.query.step as WorkspaceStep) : 'selection',
  );
  const caseId = ref<string | null>(
    typeof route.query.caseId === 'string' ? route.query.caseId : null,
  );
  /** URL 是否显式指定了 step；未指定时按案例状态推导 */
  const hasExplicitStep = isWorkspaceStep(route.query.step);
  const createdSessionId = ref<string | null>(null);
  const loadError = ref('');
  const creating = ref(false);
  const gatesRunning = ref(false);
  /** 防止刷新后重复触发三关校验 */
  const gatesAttempted = ref(false);

  // —— 页面级缓存：指标列表仅查一次；口径按指标缓存，离开页面或指标重查时清空 ——
  const rules = ref<RuleItem[]>([]);
  const rulesLoading = ref(false);
  const rulesLoaded = ref(false);
  const profilesCache = ref<Record<string, RuleProfile[]>>({});

  /** 进入 /diagnosis 时调用，整页生命周期内只查一次指标 */
  async function loadRules() {
    if (rulesLoaded.value) return;
    rulesLoading.value = true;
    try {
      // 重新查询指标前清空口径缓存（口径随指标变化，避免陈旧映射）
      profilesCache.value = {};
      rules.value = await getRulesList();
      rulesLoaded.value = true;
    } catch {
      rules.value = [];
    } finally {
      rulesLoading.value = false;
    }
  }

  /** 按指标取口径：命中缓存直接返回，未命中才请求并写入缓存 */
  async function getProfiles(ruleId: string): Promise<RuleProfile[]> {
    const cached = profilesCache.value[ruleId];
    if (cached) return cached;
    const list = await getRuleProfiles(ruleId);
    profilesCache.value = { ...profilesCache.value, [ruleId]: list };
    return list;
  }

  /** 离开 /diagnosis 页面：清空指标与口径缓存 */
  onUnmounted(() => {
    rules.value = [];
    rulesLoaded.value = false;
    profilesCache.value = {};
  });

  const snapshot = computed<DiagnosisCaseSnapshot | null>(() =>
    caseId.value ? (diagnosis.getCase(caseId.value) ?? null) : null,
  );

  /** 任务是否已结束（status 或 currentStep 任一为 COMPLETED 即判定只读） */
  const caseCompleted = computed(() => {
    if (!caseId.value) return false;
    const s = diagnosis.getCase(caseId.value);
    if (!s) return false;
    return s.status === DIAGNOSIS_STEP.COMPLETED || s.currentStep === DIAGNOSIS_STEP.COMPLETED;
  });

  /** 已有案例时，回填第一步所需的指标/口径/统计周期 */
  const candidateRuleIds = computed(() => {
    const value =
      typeof route.query.candidateRuleIds === 'string' ? route.query.candidateRuleIds : '';
    return value
      .split(',')
      .map((item) => item.trim())
      .filter(Boolean);
  });

  const prefill = computed<SelectionPrefill | null>(() => {
    const id = caseId.value;
    if (id) {
      const s = diagnosis.getCase(id);
      if (!s) return null;
      return {
        ruleId: s.ruleId,
        profileId: s.profileId,
        statStart: (s.caseInput?.statStart as string | undefined) ?? '',
        statEnd: (s.caseInput?.statEnd as string | undefined) ?? '',
      };
    }
    const value: SelectionPrefill = {
      ruleId: typeof route.query.ruleId === 'string' ? route.query.ruleId : undefined,
      profileId: typeof route.query.profileId === 'string' ? route.query.profileId : undefined,
      statStart: typeof route.query.statStart === 'string' ? route.query.statStart : undefined,
      statEnd: typeof route.query.statEnd === 'string' ? route.query.statEnd : undefined,
    };
    return Object.values(value).some(Boolean) ? value : null;
  });

  function syncUrl() {
    const query: Record<string, string> = { step: step.value };
    if (caseId.value) query.caseId = caseId.value;
    router.replace({ path: '/diagnosis', query }).catch(() => undefined);
  }

  // 监听 URL（手动刷新 / 外部跳转），保持状态一致
  watch(
    () => route.query,
    (q) => {
      const qStep = q.step;
      if (isWorkspaceStep(qStep)) step.value = qStep;
      const qCase = typeof q.caseId === 'string' ? q.caseId : null;
      if (qCase !== caseId.value) {
        caseId.value = qCase;
        if (qCase && !diagnosis.getCase(qCase)) void loadCase(qCase);
      }
    },
  );

  async function loadCase(id: string) {
    loadError.value = '';
    try {
      await diagnosis.loadCase(id);
      deriveStepIfNeeded();
    } catch (e) {
      loadError.value = e instanceof Error ? e.message : '加载排查任务失败';
    }
  }

  /** URL 未显式指定 step 时，按案例三关结果推导初始步骤 */
  function deriveStepIfNeeded() {
    if (hasExplicitStep || !caseId.value) return;
    const snap = diagnosis.getCase(caseId.value);
    if (!snap) return;
    const allPassed = [GATE.SCHEMA, GATE.EVENT, GATE.VALUE].every(
      (n) => snap.gateResults.find((g) => Number(g.gate) === n)?.status === 'PASSED',
    );
    step.value = allPassed ? 'lineage' : 'data';
  }

  function clearError() {
    loadError.value = '';
  }

  function goStep(next: WorkspaceStep) {
    step.value = next;
    syncUrl();
  }

  async function runGates(id: string) {
    if (gatesRunning.value) return;
    gatesRunning.value = true;
    gatesAttempted.value = true;
    try {
      const current = diagnosis.getCase(id);
      // 后端建案后初始步骤为 CALIBER_CONFIRMATION，需先确认口径再连跑三关
      if (current && current.currentStep === 'CALIBER_CONFIRMATION') {
        // 后端 confirmCaliber 要求 payload.confirmed === true，否则返回 CALIBER_NOT_CONFIRMED
        await diagnosis.submitAction(id, DIAGNOSIS_ACTION.CONFIRM_CALIBER, { confirmed: true });
      }
      // 「修复后重新准备」重试：对已阻塞的关卡重新校验（RECHECK_GATE），再继续后续关卡
      const blocked = diagnosis
        .getCase(id)
        ?.gateResults.find((g) => String(g.status) === 'BLOCKED');
      if (blocked) {
        await diagnosis.submitAction(id, DIAGNOSIS_ACTION.RECHECK_GATE, {
          gate: Number(blocked.gate),
        });
      }
      await diagnosis.advanceGates(id);
    } catch (e) {
      loadError.value = e instanceof Error ? e.message : '数据核对失败';
    } finally {
      gatesRunning.value = false;
    }
  }

  /** 进入数据确认步骤时，按需触发三关校验 */
  function ensureGatesRan() {
    if (!caseId.value) return;
    const snap = diagnosis.getCase(caseId.value);
    const hasGates = (snap?.gateResults?.length ?? 0) > 0;
    if (!hasGates && !gatesRunning.value && !gatesAttempted.value) {
      void runGates(caseId.value);
    }
  }

  async function createCase(input: CreateCaseInput) {
    creating.value = true;
    loadError.value = '';
    try {
      let sessionId = chat.backendSessionId;
      if (!sessionId) sessionId = await chat.createNewSession();
      if (!sessionId) throw new Error('创建会话失败，请稍后重试');
      const modelId = chat.currentModelId ?? chat.models.find((m) => m.available)?.id ?? null;
      if (!modelId) throw new Error('请先在右上角选择一个对话模型');

      const snap = await diagnosis.createCase({
        sessionId,
        ruleId: input.ruleId,
        profileId: input.profileId,
        statStart: input.statStart,
        statEnd: input.statEnd,
        modelId,
        caseInput: { statStart: input.statStart, statEnd: input.statEnd },
      });
      createdSessionId.value = sessionId;
      caseId.value = snap.caseId;
      // 让聊天会话写入入口锚点，返回时可见入口卡
      try {
        await chat.loadSessionMessages(sessionId);
      } catch {
        /* 入口卡非阻断，忽略失败 */
      }
      goStep('data');
      await runGates(snap.caseId);
      return snap;
    } catch (e) {
      loadError.value = e instanceof Error ? e.message : '创建排查任务失败';
      throw e;
    } finally {
      creating.value = false;
    }
  }

  function close() {
    if (createdSessionId.value) router.push(`/chat/${createdSessionId.value}`);
    else router.push('/chat');
  }

  return {
    step,
    caseId,
    snapshot,
    prefill,
    candidateRuleIds,
    rules,
    rulesLoading,
    loadRules,
    getProfiles,
    loadError,
    creating,
    gatesRunning,
    goStep,
    loadCase,
    runGates,
    ensureGatesRan,
    createCase,
    close,
    caseCompleted,
    clearError,
    STEPS: WORKSPACE_STEPS,
  };
}
