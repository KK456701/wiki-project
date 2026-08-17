import { watch } from 'vue';
import { useRouter } from 'vue-router';
import { useChatStore } from '@/stores/chat';
import { WORKSPACE } from '@/types/chat';
import { CHAT_WORKSPACE_ROUTE } from '../constants';

export function useWorkspaceRedirect() {
  const chatStore = useChatStore();
  const router = useRouter();

  watch(
    [() => chatStore.pendingWorkspaceRedirect, () => chatStore.isStreaming],
    async ([redirect, streaming]) => {
      if (!redirect || streaming) return;
      const target = chatStore.consumeWorkspaceRedirect();
      if (!target || target.workspace !== WORKSPACE.INDICATOR_DIAGNOSIS) return;
      const query: Record<string, string> = { step: target.step, mode: target.mode };
      if (target.ruleId) query.ruleId = target.ruleId;
      if (target.profileId) query.profileId = target.profileId;
      if (target.statStart) query.statStart = target.statStart;
      if (target.statEnd) query.statEnd = target.statEnd;
      if (target.candidateIndicators?.length) {
        query.candidateRuleIds = target.candidateIndicators.map((item) => item.ruleId).join(',');
      }
      await router.push({
        path: CHAT_WORKSPACE_ROUTE.INDICATOR_DIAGNOSIS,
        query,
      });
    },
    { flush: 'post' },
  );
}
