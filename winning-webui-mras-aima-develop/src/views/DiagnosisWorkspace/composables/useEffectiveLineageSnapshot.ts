import { computed, type Ref } from 'vue';
import { useDiagnosisStore } from '@/stores/diagnosis';
import type { DiagnosisCaseSnapshot } from '@/types/diagnosis';

export function useEffectiveLineageSnapshot(
  getCaseId: () => string | null,
  generated: Ref<DiagnosisCaseSnapshot | null>,
) {
  const diagnosis = useDiagnosisStore();
  return computed(() => {
    const caseId = getCaseId();
    return (caseId ? diagnosis.getCase(caseId) : null) ?? generated.value ?? null;
  });
}

export function storeLineageSqlRepair(caseId: string | null | undefined, value: unknown) {
  if (!caseId) return false;
  sessionStorage.setItem(`diagnosis-sql-repair:${caseId}`, JSON.stringify(value));
  return true;
}
