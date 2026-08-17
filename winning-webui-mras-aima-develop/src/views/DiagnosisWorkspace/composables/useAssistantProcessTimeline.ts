import { onBeforeUnmount, reactive, ref, watch, type ComputedRef } from 'vue';
import {
  assistantProcessDetail,
  type AssistantDisplayEvent,
} from '@/views/DiagnosisWorkspace/assistant-events';

const CHARACTER_INTERVAL_MS = 28;

export function useAssistantProcessTimeline(events: ComputedRef<AssistantDisplayEvent[]>) {
  const expanded = ref<string[]>([]);
  const revealed = reactive<Record<string, string>>({});
  const previousStatus = new Map<string, string>();
  const timers = new Map<string, number>();
  const collapseTimers = new Map<string, number>();

  function clearTimer(key: string) {
    const timer = timers.get(key);
    if (timer != null) window.clearInterval(timer);
    timers.delete(key);
  }

  function clearCollapseTimer(key: string) {
    const timer = collapseTimers.get(key);
    if (timer != null) window.clearTimeout(timer);
    collapseTimers.delete(key);
  }

  function reveal(event: AssistantDisplayEvent) {
    clearTimer(event.key);
    const current = revealed[event.key] ?? '';
    revealed[event.key] = event.text.startsWith(current) ? current : '';
    if (revealed[event.key] === event.text) return;
    const timer = window.setInterval(() => {
      const length = revealed[event.key]?.length ?? 0;
      revealed[event.key] = event.text.slice(0, length + 1);
      if (revealed[event.key] === event.text) clearTimer(event.key);
    }, CHARACTER_INTERVAL_MS);
    timers.set(event.key, timer);
  }

  function collapseAfterReveal(event: AssistantDisplayEvent) {
    clearCollapseTimer(event.key);
    const remaining = Math.max(0, event.text.length - (revealed[event.key]?.length ?? 0));
    const timer = window.setTimeout(
      () => {
        expanded.value = expanded.value.filter((key) => key !== event.key);
        collapseTimers.delete(event.key);
      },
      remaining * CHARACTER_INTERVAL_MS + 500,
    );
    collapseTimers.set(event.key, timer);
  }

  watch(
    events,
    (values) => {
      for (const event of values) {
        if (event.kind !== 'THINKING') continue;
        const status = event.status.toUpperCase();
        const wasRunning = previousStatus.get(event.key) === 'RUNNING';
        if (status === 'RUNNING') {
          clearCollapseTimer(event.key);
          if (!expanded.value.includes(event.key)) expanded.value.push(event.key);
          reveal(event);
        } else if (wasRunning) {
          if (!expanded.value.includes(event.key)) expanded.value.push(event.key);
          reveal(event);
          collapseAfterReveal(event);
        } else {
          clearTimer(event.key);
          revealed[event.key] = event.text;
        }
        previousStatus.set(event.key, status);
      }
    },
    { immediate: true, deep: true },
  );

  function detail(event: AssistantDisplayEvent): string {
    return assistantProcessDetail(event, revealed[event.key]);
  }

  onBeforeUnmount(() => {
    timers.forEach((_, key) => clearTimer(key));
    collapseTimers.forEach((_, key) => clearCollapseTimer(key));
  });

  return { expanded, detail };
}
