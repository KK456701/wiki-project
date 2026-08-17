export function resultRecord(value: unknown): Record<string, unknown> {
  if (Array.isArray(value)) return resultRecord(value[0]);
  return value && typeof value === 'object' ? (value as Record<string, unknown>) : {};
}

function metricByChinese(row: Record<string, unknown>, marker: string): unknown {
  const key = Object.keys(row).find((item) => item.includes(marker));
  return key ? row[key] : undefined;
}

export function executionMetric(
  row: Record<string, unknown>,
  kind: 'numerator' | 'denominator' | 'result',
): unknown {
  if (kind === 'result') {
    return row.resultValue ?? row.result ?? metricByChinese(row, '监测情况');
  }
  if (kind === 'numerator') {
    return row.numeratorCount ?? row.numerator ?? metricByChinese(row, '分子');
  }
  return row.denominatorCount ?? row.denominator ?? metricByChinese(row, '分母');
}

export function displayExecutionMetric(value: unknown): string {
  if (value === null || value === undefined || value === '') return '—';
  if (typeof value === 'number') return Number.isInteger(value) ? String(value) : value.toFixed(6);
  return String(value);
}
