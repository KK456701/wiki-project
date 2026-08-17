import { computed, type ComputedRef } from 'vue';
import { ATTAINMENT_LABEL, TARGET_DIRECTION_SYMBOL } from '@/constants/diagnosis';

export function useAttainmentDisplay(evidence: ComputedRef<Record<string, unknown>>) {
  const resultValue = computed(() => evidence.value.resultValue ?? '-');
  const numeratorCount = computed(() => evidence.value.numeratorCount ?? '-');
  const denominatorCount = computed(() => evidence.value.denominatorCount ?? '-');
  const targetValue = computed(() => evidence.value.targetValue);
  const direction = computed(() => String(evidence.value.targetDirection ?? '').trim());
  const symbol = computed(() => TARGET_DIRECTION_SYMBOL[direction.value.toLowerCase()] ?? '');
  const hasBasis = computed(
    () => resultValue.value !== '-' && targetValue.value != null && symbol.value !== '',
  );
  const attainmentLabel = computed(() => {
    if (!hasBasis.value) return ATTAINMENT_LABEL.PENDING;
    const label = String(evidence.value.attainmentLabel ?? '');
    return Object.values(ATTAINMENT_LABEL).includes(
      label as (typeof ATTAINMENT_LABEL)[keyof typeof ATTAINMENT_LABEL],
    )
      ? label
      : ATTAINMENT_LABEL.PENDING;
  });
  const attainmentClass = computed(() => ({
    'attainment--met': attainmentLabel.value === ATTAINMENT_LABEL.MET,
    'attainment--not-met': attainmentLabel.value === ATTAINMENT_LABEL.NOT_MET,
    'attainment--pending': attainmentLabel.value === ATTAINMENT_LABEL.PENDING,
  }));
  const targetText = computed(() =>
    hasBasis.value ? `目标值 ${symbol.value} ${String(targetValue.value)}` : '目标值—',
  );
  const attainmentSuffix = computed(() => `（${attainmentLabel.value}，${targetText.value}）`);

  return {
    resultValue,
    numeratorCount,
    denominatorCount,
    attainmentLabel,
    attainmentClass,
    targetText,
    attainmentSuffix,
  };
}
