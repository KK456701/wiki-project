const NUMERIC_ID_PATTERN = /^\d+$/;
const POSSIBLE_ENCOUNTER_ID_MIN_LENGTH = 12;

export function isLikelyEncounterId(value: string): boolean {
  const normalized = value.trim();
  return (
    NUMERIC_ID_PATTERN.test(normalized) && normalized.length >= POSSIBLE_ENCOUNTER_ID_MIN_LENGTH
  );
}
