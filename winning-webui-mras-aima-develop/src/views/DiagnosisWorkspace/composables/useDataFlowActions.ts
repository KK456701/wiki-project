import { ref, computed, onBeforeUnmount } from 'vue';
import type { DataFlowNode } from '@/types/chat';
import { DATA_FLOW_NODE_TYPE } from '@/types/chat';
import type { AiScopeTarget, DiagnosisCaseSnapshot } from '@/types/diagnosis';
import { DIAGNOSIS_ACTION } from '@/constants/diagnosis';
import { useDiagnosisStore } from '@/stores/diagnosis';

/** 实施方 SQL 要求证据类型（对齐后端 SUBMIT_EVIDENCE 契约） */
const SQL_REQUIREMENT_TYPE = 'IMPLEMENTER_SQL_REQUIREMENT';

/** 候选 SQL 生成模式（对齐后端 generationMode 契约） */
const GENERATION_MODE = {
  /** AI 分析要求并自动改写 */
  AI_MODIFY: 'AI_MODIFY',
  /** 实施人员直接编辑 SQL */
  DIRECT_EDIT: 'DIRECT_EDIT',
} as const;

/** AI 生成 SQL 的入参（direct 直编 / ai 生成两种模式） */
export interface SqlGeneratePayload {
  mode: 'direct' | 'ai';
  requirement: string;
  candidateSql: string;
  scopeTargets: AiScopeTarget[];
}

/** 节点类型 → 疑似问题层（仅概览 / 源表抽取 SQL 支持改写） */
function suspectedLayerOf(node: DataFlowNode): string {
  return node.nodeType === DATA_FLOW_NODE_TYPE.OVERVIEW_SQL ? 'OVERVIEW' : 'SOURCE_EXTRACT';
}

/** 节点类型 → 重新执行层（源表抽取 / 概览 SQL 支持重新执行） */
function rerunLayerOf(node: DataFlowNode): string {
  return node.nodeType === DATA_FLOW_NODE_TYPE.OVERVIEW_SQL ? 'OVERVIEW' : 'SOURCE_EXTRACT';
}

/** 可触发 AI 生成 SQL 的节点类型（排除只读的明细 / 科室 / 事件节点） */
export function isSqlGeneratableNode(node: DataFlowNode | null | undefined): boolean {
  if (!node) return false;
  return (
    node.nodeType === DATA_FLOW_NODE_TYPE.SOURCE_EXTRACT_SQL ||
    node.nodeType === DATA_FLOW_NODE_TYPE.OVERVIEW_SQL
  );
}

/** 从生成动作返回的快照中提取可读的候选 SQL（结构不确定，按候选字段收敛） */
function candidateSqlOf(snapshot: { candidateSql: Record<string, unknown> } | null): string {
  const candidate = snapshot?.candidateSql;
  if (!candidate || typeof candidate !== 'object') return '';
  const record = candidate;
  const sql = record.candidateSqlExecutable ?? record.candidateSql ?? record.sql ?? '';
  return typeof sql === 'string' ? sql.trim() : '';
}

/** 从候选快照提取正式 SQL（用于比对是否发生实际变化） */
function originalSqlOf(snapshot: { candidateSql: Record<string, unknown> } | null): string {
  const candidate = snapshot?.candidateSql;
  if (!candidate || typeof candidate !== 'object') return '';
  const record = candidate;
  const sql = record.originalSqlExecutable ?? record.originalSql ?? '';
  return typeof sql === 'string' ? sql.trim() : '';
}

function comparableDateTime(value: unknown): string {
  const parts = String(value ?? '').match(/\d+/g) ?? [];
  if (parts.length < 3) return '';
  const normalized = [...parts.slice(0, 6)];
  while (normalized.length < 6) normalized.push('0');
  const [year, month, day, hour, minute, second] = normalized.map((part, index) =>
    index === 0 ? part.padStart(4, '0') : part.padStart(2, '0'),
  );
  return `${year}-${month}-${day}T${hour}:${minute}:${second}`;
}

/** 提取最新一条 IMPLEMENTER_SQL_REQUIREMENT 证据的失败原因（对齐 latestRequirementAnalysis） */
function generationFailureOf(snapshot: DiagnosisCaseSnapshot | null): {
  failureReason: string;
  nextAction: string;
} {
  const evidence = Array.isArray(snapshot?.evidence) ? snapshot.evidence : [];
  for (let index = evidence.length - 1; index >= 0; index -= 1) {
    const item = evidence[index] as { type?: string; requirementAnalysis?: unknown };
    if (typeof item !== 'object' || item === null) continue;
    if (String(item.type ?? '') !== SQL_REQUIREMENT_TYPE) continue;
    const analysis = item.requirementAnalysis;
    if (!analysis || typeof analysis !== 'object') continue;
    const record = analysis as Record<string, unknown>;
    return {
      failureReason: String(record.failureReason ?? ''),
      nextAction: String(record.nextAction ?? ''),
    };
  }
  return { failureReason: '', nextAction: '' };
}

/**
 * 数据链路「AI 生成对应 SQL」动作（源表抽取 / 概览 SQL 节点）。
 *
 * 与「重新抽取」类似，均是 SUBMIT_EVIDENCE + IMPLEMENTER_SQL_REQUIREMENT 的封装，
 * 从后端返回的快照中提取候选 SQL 供右侧详情展示，避免生成成功后无任何反馈。
 *
 * @param getCaseId 取当前排查案例 ID 的 getter；无 caseId 时动作不可用
 */
export function useSqlGeneration(getCaseId: () => string | null) {
  const diagnosis = useDiagnosisStore();
  const generating = ref(false);
  const generateResult = ref('');
  const generateError = ref('');
  const generationLayer = ref('');

  /** 生成动作返回的快照（candidateSql / shadowTrial 唯一来源） */
  const generationSnapshot = ref<DiagnosisCaseSnapshot | null>(null);
  /** 影子试跑中 */
  const trialRunning = ref(false);
  /** 影子试跑错误 */
  const trialError = ref('');

  async function handleGenerate(node: DataFlowNode, payload: SqlGeneratePayload) {
    const cid = getCaseId();
    if (!cid) return;

    const isDirect = payload.mode === 'direct';
    // 直编模式：以当前正式 SQL 为基准，仅提交差异后的候选 SQL；
    // AI 模式：与参考实现 aiRequirementText() 对齐，仅勾选排除对象时也能生成。
    const requirementText = isDirect
      ? '实施人员直接编辑当前正式 SQL'
      : [payload.requirement.trim(), requirementTextOf(payload.scopeTargets)]
          .filter(Boolean)
          .join('；');

    if (!isDirect && !requirementText) return;
    if (isDirect && payload.candidateSql.trim() === (node.templateSql || node.sql || '').trim()) {
      generateError.value = '请先修改 SQL 内容后再保存。';
      return;
    }

    generating.value = true;
    generationLayer.value = suspectedLayerOf(node);
    generateResult.value = '';
    generateError.value = '';
    trialError.value = '';
    generationSnapshot.value = null;

    // 数据确认已固化的公共规则与提交时间，需透传给 SUBMIT_EVIDENCE（对齐 readonly generateCandidate）。
    const current = diagnosis.getCase(cid);
    const confirmation = current?.dataConfirmation as Record<string, unknown> | undefined;
    const publicRuleIds = Array.isArray(confirmation?.publicRuleIds)
      ? (confirmation.publicRuleIds as unknown[]).map(String)
      : [];

    try {
      const updated = await diagnosis.submitAction(cid, DIAGNOSIS_ACTION.SUBMIT_EVIDENCE, {
        type: SQL_REQUIREMENT_TYPE,
        suspectedLayer: suspectedLayerOf(node),
        nodeId: node.id,
        summary: `${suspectedLayerOf(node) === 'SOURCE_EXTRACT' ? '抽取 SQL' : '概览 SQL'}修改要求：${requirementText}`,
        requirement: requirementText,
        candidateSql: isDirect ? payload.candidateSql.trim() : '',
        patchConditions: [],
        scopeTargets: isDirect ? [] : payload.scopeTargets,
        publicRuleIds,
        generationMode: isDirect ? GENERATION_MODE.DIRECT_EDIT : GENERATION_MODE.AI_MODIFY,
        dataConfirmationRef: String(confirmation?.submittedAt ?? ''),
        requestAiAnalysis: !isDirect,
        deferShadowTrial: true,
      });

      generationSnapshot.value = updated;
      const sql = candidateSqlOf(updated);
      const originalSql = originalSqlOf(updated);

      if (!sql) {
        // 后端未生成候选 SQL：优先展示模型失败原因与下一步建议
        const failure = generationFailureOf(updated);
        generateError.value = failure.failureReason
          ? `${failure.failureReason}${failure.nextAction ? ` ${failure.nextAction}` : ''}`
          : '未能生成候选 SQL，请核对修改要求后重试。';
      } else if (sql === originalSql) {
        generateError.value = '候选 SQL 与当前正式 SQL 相同，请先修改条件或 SQL 内容。';
      } else {
        generateResult.value =
          '候选 SQL 已生成，您可在下方核对后执行该 SQL 或在左上方整体执行所有 SQL。';
      }
    } catch (err) {
      generateError.value = err instanceof Error ? err.message : '生成 SQL 失败。';
    } finally {
      generating.value = false;
    }
  }

  /** 对已生成的候选 SQL 执行影子试跑（RUN_SHADOW_TRIAL） */
  async function handleRunTrial() {
    const cid = getCaseId();
    if (!cid) return;
    if (!generationSnapshot.value) return;

    trialRunning.value = true;
    trialError.value = '';

    try {
      const updated = await diagnosis.submitAction(cid, DIAGNOSIS_ACTION.RUN_SHADOW_TRIAL, {});
      generationSnapshot.value = updated;

      // 后端 passed=false 或 status=FAILED 时，接口不会抛错，需要从快照判定并提示
      const shadow = updated.shadowTrial as Record<string, unknown> | undefined;
      if (shadow && (shadow.passed === false || shadow.status === 'FAILED')) {
        trialError.value = String(
          shadow.message || shadow.failureStage || '影子试跑未通过，请根据执行结果调整候选条件。',
        );
      }
    } catch (err) {
      trialError.value = err instanceof Error ? err.message : '影子试跑失败。';
    } finally {
      trialRunning.value = false;
    }
  }

  return {
    generating,
    generateResult,
    generateError,
    generationLayer,
    generationSnapshot,
    trialRunning,
    trialError,
    handleGenerate,
    handleRunTrial,
  };
}

/** 执行进度阶段切换间隔（对齐参考实现 startExecutionFlow 1.4s） */
const STAGE_INTERVAL_MS = 1400;

/** 基线整体执行进度阶段（对齐参考实现 executeWholeLineage） */
const BASELINE_TRIAL_STAGES = [
  '正在准备当前正式链路…',
  '正在校验数据库方言…',
  '正在执行正式 SQL 基线…',
  '正在计算分子分母…',
  '正在核对执行结果…',
] as const;

/** 候选链路影子试跑进度阶段（对齐参考实现 runCandidate） */
const SHADOW_TRIAL_STAGES = [
  '正在创建隔离影子环境…',
  '正在执行候选抽取 SQL…',
  '正在计算候选分子分母…',
  '正在生成差异明细…',
  '正在完成结果对账…',
] as const;

/**
 * 数据链路「整体执行」动作（对齐参考实现 executeWholeLineage）。
 *
 * - 存在已保存候选 SQL 时优先执行候选链路（RUN_SHADOW_TRIAL）；
 * - 否则执行当前正式链路基线（RUN_LINEAGE_BASELINE），
 *   选中可编辑节点时透传 layer/nodeId，使基线试跑聚焦该节点。
 *
 * 执行结果通过 submitAction 写入诊断 Store 快照，下游（节点详情等）
 * 响应式读取 Store 即可反映最新试跑数据。
 *
 * @param getCaseId 取当前排查案例 ID 的 getter；无 caseId 时动作不可用
 * @param getSelectedNode 取当前选中节点的 getter（用于透传 layer/nodeId）
 */
export function useOverallExecution(
  getCaseId: () => string | null,
  getSelectedNode: () => DataFlowNode | null,
) {
  const diagnosis = useDiagnosisStore();
  const executing = ref(false);
  const executeStage = ref('');
  const executeStages = ref<string[]>([]);
  const executeError = ref('');
  const executeResult = ref('');

  let progressTimer: ReturnType<typeof setInterval> | null = null;

  /**
   * 候选 SQL 是否已保存且可执行（对齐参考实现 savedCandidateReady）。
   * 满足三个条件：候选 SQL 与正式 SQL 不同、校验通过、非仅基线模式。
   */
  const savedCandidateReady = computed(() => {
    const cid = getCaseId();
    if (!cid) return false;
    const snapshot = diagnosis.getCase(cid);
    if (!snapshot) return false;
    const candidate = snapshot.candidateSql;
    if (!candidate || typeof candidate !== 'object') return false;
    const executable = String(candidate.candidateSqlExecutable ?? candidate.sql ?? '').trim();
    const original = String(candidate.originalSqlExecutable ?? candidate.originalSql ?? '').trim();
    if (!executable || executable === original) return false;
    const validation = candidate.validation as Record<string, unknown> | undefined;
    if (!validation || validation.ok !== true) return false;
    if (candidate.baselineOnly === true) return false;
    return true;
  });

  const cachedResultReady = computed(() => {
    const cid = getCaseId();
    const snapshot = cid ? diagnosis.getCase(cid) : undefined;
    if (!snapshot) return false;
    const shadow = snapshot.shadowTrial;
    const candidate = snapshot.candidateSql;
    const executedHash = String(shadow.executedSqlHash ?? '');
    const currentHash = String(candidate.candidateSqlHash ?? '');
    const start = comparableDateTime(snapshot.caliberSnapshot.timeRange.start);
    const end = comparableDateTime(snapshot.caliberSnapshot.timeRange.end);
    return (
      shadow.completed === true &&
      Boolean(executedHash) &&
      executedHash === currentHash &&
      String(shadow.knowledgeReleaseId ?? '') === snapshot.knowledgeReleaseId &&
      comparableDateTime(shadow.statStart) === start &&
      comparableDateTime(shadow.statEnd) === end
    );
  });

  function startStages(stages: readonly string[]) {
    stopStages();
    executeStages.value = [...stages];
    let index = 0;
    executeStage.value = stages[0] ?? '';
    progressTimer = setInterval(() => {
      if (index < stages.length - 1) {
        index += 1;
        executeStage.value = stages[index] ?? '';
      }
    }, STAGE_INTERVAL_MS);
  }

  function stopStages(clearStage = true) {
    if (progressTimer) {
      clearInterval(progressTimer);
      progressTimer = null;
    }
    if (clearStage) executeStage.value = '';
  }

  async function handleOverallExecution() {
    const cid = getCaseId();
    if (!cid) return;

    executeError.value = '';
    executeResult.value = '';

    if (cachedResultReady.value) {
      executeStages.value = [];
      executeStage.value = '';
      executeResult.value = '已打开最近一次冻结结果。';
      return;
    }

    executing.value = true;

    try {
      if (savedCandidateReady.value) {
        startStages(SHADOW_TRIAL_STAGES);
        const updated = await diagnosis.submitAction(cid, DIAGNOSIS_ACTION.RUN_SHADOW_TRIAL, {});
        const shadow = updated.shadowTrial as Record<string, unknown> | undefined;
        if (shadow && (shadow.passed === false || shadow.status === 'FAILED')) {
          executeError.value = String(
            shadow.message || shadow.failureStage || '影子试跑未通过，请根据执行结果调整候选条件。',
          );
        } else {
          executeResult.value = '整体执行完成，候选链路已通过影子试跑。';
        }
      } else {
        startStages(BASELINE_TRIAL_STAGES);
        const node = getSelectedNode();
        const payload =
          node && isSqlGeneratableNode(node) ? { layer: rerunLayerOf(node), nodeId: node.id } : {};
        const updated = await diagnosis.submitAction(
          cid,
          DIAGNOSIS_ACTION.RUN_LINEAGE_BASELINE,
          payload,
        );
        const shadow = updated.shadowTrial as Record<string, unknown> | undefined;
        if (shadow && (shadow.passed === false || shadow.status === 'FAILED')) {
          executeError.value = String(
            `${shadow.failureStage ?? '执行阶段'}：${shadow.message ?? '基线试跑未通过'}`,
          );
        } else {
          executeResult.value = '整体执行完成。';
        }
      }
    } catch (err) {
      executeError.value = err instanceof Error ? err.message : '整体执行失败。';
    } finally {
      stopStages(false);
      executeStage.value = executeError.value ? '执行失败' : '执行完成';
      executing.value = false;
    }
  }

  onBeforeUnmount(stopStages);

  return {
    executing,
    executeStage,
    executeStages,
    executeError,
    executeResult,
    savedCandidateReady,
    cachedResultReady,
    handleOverallExecution,
  };
}

/** 把选中的排除对象转换为可读的自然语言要求（对齐参考实现 aiRequirementText） */
function requirementTextOf(scopeTargets: AiScopeTarget[]): string {
  if (!scopeTargets.length) return '';
  const parts = scopeTargets.map((target) => {
    const labels = target.labels.filter(Boolean).join('、');
    return target.targetType === 'RECORD'
      ? `排除这些疑似多算记录：${labels}`
      : `核对并排除科室范围：${labels}`;
  });
  return parts.join('；');
}
