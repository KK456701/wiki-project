import { computed, ref, type Ref } from 'vue';
import { useChatStore } from '@/stores/chat';
import type { BatchResultItem } from '@/types/chat';
import {
  analyzeBatch,
  BATCH_ANALYSIS_ACTION,
  inspectIndicator,
  type BatchAnalysisAction,
} from '@/services/indicator-inspection';

export const BATCH_ANALYSIS_UI_ACTION = {
  CHECKLIST: 'checklist',
  QUALITY_REVIEW: 'quality_review',
} as const;

export type BatchAnalysisUiAction =
  (typeof BATCH_ANALYSIS_UI_ACTION)[keyof typeof BATCH_ANALYSIS_UI_ACTION];

const ACTION_MAP: Record<BatchAnalysisUiAction, BatchAnalysisAction> = {
  [BATCH_ANALYSIS_UI_ACTION.CHECKLIST]: BATCH_ANALYSIS_ACTION.CONFIRMATION_CHECKLIST,
  [BATCH_ANALYSIS_UI_ACTION.QUALITY_REVIEW]: BATCH_ANALYSIS_ACTION.DATA_QUALITY_REVIEW,
};

export function useBatchAnalysis(results: Ref<BatchResultItem[]>) {
  const chat = useChatStore();
  const runningKey = ref('');
  const title = ref('');
  const answer = ref('');
  const error = ref('');
  const batchRunId = computed(() => results.value.find((item) => item.batchRunId)?.batchRunId);

  async function run(key: string, label: string, task: () => Promise<{ answer: string }>) {
    if (runningKey.value) return;
    runningKey.value = key;
    title.value = label;
    answer.value = '';
    error.value = '';
    try {
      answer.value = (await task()).answer;
    } catch (reason) {
      error.value = reason instanceof Error ? reason.message : '分析失败，请稍后重试';
    } finally {
      runningKey.value = '';
    }
  }

  async function runBatch(action: BatchAnalysisUiAction) {
    const id = batchRunId.value;
    if (!id) {
      error.value = '当前结果缺少批次编号，无法生成分析';
      return;
    }
    const label = action === BATCH_ANALYSIS_UI_ACTION.CHECKLIST ? '待确认清单' : '数据问题分析';
    await run(action, label, () => analyzeBatch(id, ACTION_MAP[action]));
  }

  async function inspect(item: BatchResultItem) {
    const id = item.batchRunId ?? batchRunId.value;
    const modelId = chat.currentModelId ?? chat.models.find((model) => model.available)?.id;
    if (!id || !modelId) {
      error.value = !id ? '当前结果缺少批次编号，无法进入排查' : '请先选择可用模型';
      return;
    }
    const key = `inspect:${item.ruleId}:${item.profileId ?? ''}`;
    await run(key, `${item.ruleName}排查建议`, () =>
      inspectIndicator({
        batchRunId: id,
        indicatorId: item.ruleId,
        profileId: item.profileId,
        modelId,
      }),
    );
  }

  return { runningKey, title, answer, error, runBatch, inspect };
}
