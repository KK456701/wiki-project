<script setup lang="ts">
import { computed, ref } from 'vue';
import ClarificationEvidenceDetails from '@/views/DiagnosisWorkspace/components/ClarificationEvidenceDetails.vue';
import type { AssistantConversation, AutonomousRun } from '@/types/diagnosis';
import type { AssistantGuidanceTurn } from '@/views/DiagnosisWorkspace/assistant';
import { assistantConversationTurns } from '@/views/DiagnosisWorkspace/assistant-events';
import AssistantProcessList from '@/views/DiagnosisWorkspace/components/AssistantProcessList.vue';

const props = defineProps<{
  conversation?: AssistantConversation;
  autonomousRun: AutonomousRun;
  viewingHistory: boolean;
  guidanceTurns: AssistantGuidanceTurn[];
}>();

function objectList(value: unknown): Array<Record<string, unknown>> {
  return Array.isArray(value)
    ? value.filter(
        (item): item is Record<string, unknown> =>
          Boolean(item) && typeof item === 'object' && !Array.isArray(item),
      )
    : [];
}

const patientClarification = computed(() => props.conversation?.type === 'PATIENT_CLARIFICATION');
const run = computed<AutonomousRun>(() => {
  if (patientClarification.value) return {};
  if (props.conversation?.type === 'AUTONOMOUS') {
    return props.conversation.autonomousRun ?? {};
  }
  return props.autonomousRun;
});
const messages = computed(() => objectList(props.conversation?.messages));
const turns = computed(() => assistantConversationTurns(run.value));
const hasCurrentAutonomous = computed(() => Boolean(run.value.conversationId));
const evidenceOpen = ref(false);

function messageRole(message: Record<string, unknown>): string {
  return String(message.role ?? 'USER').toUpperCase();
}

function messageText(message: Record<string, unknown>): string {
  return String(message.content ?? message.userMessage ?? message.answer ?? '');
}
</script>

<template>
  <div
    v-if="guidanceTurns.length || patientClarification || hasCurrentAutonomous"
    class="assistant-conversation"
  >
    <template v-if="guidanceTurns.length">
      <div v-for="(turn, index) in guidanceTurns" :key="index" class="assistant-turn">
        <div class="assistant-message is-user mb-3">
          <p class="text-body-medium">{{ turn.userMessage }}</p>
        </div>
        <div v-if="turn.assistantMessage" class="assistant-message is-assistant mb-3">
          <p class="text-body-medium">{{ turn.assistantMessage }}</p>
        </div>
        <div v-else class="assistant-guidance-pending mb-3" aria-live="polite">
          <v-progress-circular indeterminate color="primary" size="16" width="2" />
          <span class="text-body-small text-medium-emphasis">正在理解你的问题…</span>
        </div>
      </div>
    </template>

    <template v-else-if="patientClarification">
      <div
        v-for="(message, index) in messages"
        :key="`${String(message.turnId ?? message.createdAt ?? 'message')}-${index}`"
        class="mb-3"
      >
        <div
          class="assistant-message"
          :class="messageRole(message) === 'USER' ? 'is-user' : 'is-assistant'"
        >
          <div
            v-if="message.pending === true"
            class="assistant-guidance-pending"
            aria-live="polite"
          >
            <v-progress-circular indeterminate color="primary" size="16" width="2" />
            <span class="text-body-small text-medium-emphasis">正在核验患者统计情况…</span>
          </div>
          <p v-else class="text-body-medium">{{ messageText(message) }}</p>
        </div>
      </div>
    </template>

    <div v-for="turn in turns" v-else :key="turn.key" class="assistant-turn">
      <div v-if="turn.userMessage" class="assistant-message is-user mb-3">
        <p class="text-body-medium">{{ turn.userMessage }}</p>
      </div>
      <AssistantProcessList v-if="turn.processEvents.length" :events="turn.processEvents" />
      <div
        v-for="event in turn.replyEvents"
        :key="event.key"
        class="assistant-message is-assistant mb-3"
      >
        <p class="text-body-medium">{{ event.text }}</p>
      </div>
    </div>

    <section v-if="conversation?.clarification" class="clarification-evidence">
      <v-btn
        variant="text"
        density="compact"
        class="clarification-evidence__toggle px-0"
        :append-icon="evidenceOpen ? 'mdi-chevron-up' : 'mdi-chevron-down'"
        @click="evidenceOpen = !evidenceOpen"
      >
        查看核验明细
      </v-btn>
      <ClarificationEvidenceDetails
        v-if="evidenceOpen"
        :clarification="conversation.clarification"
      />
    </section>
  </div>
</template>

<style lang="scss" scoped>
.assistant-conversation {
  background: transparent;
}

.assistant-guidance-pending {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 8px 12px;
}

.assistant-message {
  max-width: 88%;
  padding: 10px 12px;
  border: 1px solid rgba(var(--v-border-color), var(--v-border-opacity));
  border-radius: 6px;

  &.is-user {
    margin-left: auto;
    background: rgba(var(--v-theme-primary), 0.07);
    border-color: rgba(var(--v-theme-primary), 0.24);
  }

  &.is-assistant {
    background: rgb(var(--v-theme-surface));
  }

  p {
    margin: 0;
    white-space: pre-wrap;
  }
}

.assistant-turn + .assistant-turn {
  margin-top: 14px;
}

.clarification-evidence {
  margin-top: 6px;
}

.clarification-evidence__toggle {
  min-width: 0;
  font-size: 13px;
  text-transform: none;
}
</style>
