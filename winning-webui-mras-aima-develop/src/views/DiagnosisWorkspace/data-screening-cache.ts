import type { DiagnosisDataScreening } from '@/types/diagnosis';

const CACHE = new Map<string, DiagnosisDataScreening>();

export function screeningCacheKey(
  caseId: string,
  overviewSqlHash: unknown,
  statStart: unknown,
  statEnd: unknown,
) {
  return [
    caseId,
    String(overviewSqlHash ?? ''),
    String(statStart ?? ''),
    String(statEnd ?? ''),
  ].join(':');
}

export function getScreeningCache(key: string) {
  return CACHE.get(key);
}

export function setScreeningCache(key: string, value: DiagnosisDataScreening) {
  CACHE.set(key, value);
}

export function clearScreeningCache(caseId: string) {
  for (const key of CACHE.keys()) if (key.startsWith(`${caseId}:`)) CACHE.delete(key);
}
