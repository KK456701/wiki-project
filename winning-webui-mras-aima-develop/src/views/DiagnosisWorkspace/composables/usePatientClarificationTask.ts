import { ref } from 'vue';
import type { DiagnosisDetailRow, PatientClarificationDirection } from '@/types/diagnosis';
import type { PatientOption } from '@/views/DiagnosisWorkspace/assistant';

type ClientIdCrypto = Pick<Crypto, 'getRandomValues'> & Partial<Pick<Crypto, 'randomUUID'>>;

const UUID_BYTE_COUNT = 16;
const DEFAULT_CLARIFICATION_DIRECTION: PatientClarificationDirection = 'OVER_COUNTED';

export function createClientId(source: ClientIdCrypto | undefined = globalThis.crypto): string {
  if (typeof source?.randomUUID === 'function') {
    return source.randomUUID();
  }

  const bytes = new Uint8Array(UUID_BYTE_COUNT);
  if (source) {
    source.getRandomValues(bytes);
  } else {
    for (let index = 0; index < bytes.length; index += 1) {
      bytes[index] = Math.floor(Math.random() * 256);
    }
  }

  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (value) => value.toString(16).padStart(2, '0'));
  return [
    hex.slice(0, 4).join(''),
    hex.slice(4, 6).join(''),
    hex.slice(6, 8).join(''),
    hex.slice(8, 10).join(''),
    hex.slice(10).join(''),
  ].join('-');
}

export interface PatientClarificationApi {
  clarify: (
    row: DiagnosisDetailRow,
    userMessage?: string,
    options?: { requestId?: string; signal?: AbortSignal; conversationId?: string },
    direction?: PatientClarificationDirection,
  ) => Promise<boolean>;
  cancel: (requestId: string) => Promise<boolean>;
}

export function usePatientClarificationTask(api: PatientClarificationApi) {
  const target = ref<PatientOption>();
  const message = ref('');
  const running = ref(false);
  const stopped = ref(false);
  let requestId = '';

  function reset() {
    target.value = undefined;
    message.value = '';
    stopped.value = false;
  }

  function stage(option: PatientOption) {
    target.value = option;
    stopped.value = false;
  }

  async function submit(
    userMessage: string,
    conversationId: string | undefined,
    onCompleted: () => Promise<void>,
    onFailed: (value: string) => void,
  ): Promise<boolean> {
    if (!target.value) return false;
    message.value = userMessage.trim();
    running.value = true;
    stopped.value = false;
    const currentRequestId = createClientId();
    requestId = currentRequestId;
    try {
      const ok = await api.clarify(
        target.value.row,
        message.value,
        { requestId: currentRequestId, conversationId },
        target.value.direction ?? DEFAULT_CLARIFICATION_DIRECTION,
      );
      if (requestId !== currentRequestId || stopped.value) return true;
      if (ok) {
        target.value = undefined;
        await onCompleted();
        message.value = '';
      } else {
        onFailed(message.value);
      }
      return true;
    } finally {
      if (requestId === currentRequestId) {
        requestId = '';
        running.value = false;
      }
    }
  }

  async function stop(): Promise<boolean> {
    if (!running.value || !requestId) return false;
    const currentRequestId = requestId;
    requestId = '';
    running.value = false;
    stopped.value = true;
    await api.cancel(currentRequestId);
    return true;
  }

  return { target, message, running, stopped, reset, stage, submit, stop };
}
