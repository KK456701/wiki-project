<script setup lang="ts">
import { computed } from 'vue';
import { detailFieldLabel, formatDetailCell } from '@/components/details/detail-fields';
import type { ClarificationDirection } from '@/types/diagnosis';

const props = defineProps<{ clarification?: ClarificationDirection }>();

function record(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {};
}

function evidenceRows(value: Record<string, unknown>): Array<Record<string, unknown>> {
  const numerator = value.numeratorEvidenceRows;
  if (Array.isArray(numerator) && numerator.length)
    return numerator as Array<Record<string, unknown>>;
  const denominator = value.denominatorEvidenceRows;
  return Array.isArray(denominator) ? (denominator as Array<Record<string, unknown>>) : [];
}

const sections = computed(() => {
  const clarification = props.clarification;
  if (!clarification) return [];
  if (clarification.evidenceSections?.length) return clarification.evidenceSections;
  const directRows = evidenceRows(record(clarification));
  const nestedRows = (clarification.targetResults ?? []).flatMap((item) =>
    evidenceRows(record(item)),
  );
  const rows = directRows.length ? directRows : nestedRows;
  return rows.length ? [{ source: 'DETAIL', label: '指标相关核验字段', rows }] : [];
});

function columns(rows: Array<Record<string, unknown>>) {
  const values = new Set<string>();
  rows.forEach((row) =>
    Object.keys(row).forEach((key) => !key.startsWith('__') && values.add(key)),
  );
  return Array.from(values);
}
</script>

<template>
  <div v-if="sections.length" class="evidence-sections mt-3">
    <section v-for="section in sections" :key="section.source" class="evidence-section">
      <div class="text-label-medium mb-2">{{ section.label }}</div>
      <div class="evidence-table-wrap">
        <table class="evidence-table">
          <thead>
            <tr>
              <th v-for="field in columns(section.rows)" :key="field">
                {{ detailFieldLabel(field) }}
              </th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, rowIndex) in section.rows" :key="rowIndex">
              <td v-for="field in columns(section.rows)" :key="field">
                {{ formatDetailCell(field, row[field]) }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
    <div v-if="clarification?.missingFields?.length" class="text-body-small text-medium-emphasis">
      未获取到：{{ clarification.missingFields.join('、') }}
    </div>
  </div>
</template>

<style scoped>
.evidence-sections {
  display: grid;
  gap: 12px;
}

.evidence-section {
  min-width: 0;
  padding: 12px;
  border: 1px solid rgba(var(--v-theme-outline), 0.18);
  border-radius: 8px;
  background: rgba(var(--v-theme-on-surface), 0.025);
}

.evidence-table-wrap {
  max-width: 100%;
  overflow-x: auto;
  scrollbar-gutter: stable;
}

.evidence-table {
  width: max-content;
  min-width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}

.evidence-table th,
.evidence-table td {
  min-width: 130px;
  padding: 8px 10px;
  text-align: left;
  white-space: nowrap;
  border-bottom: 1px solid rgba(var(--v-theme-outline), 0.14);
}

.evidence-table th {
  color: rgba(var(--v-theme-on-surface), 0.62);
  font-weight: 500;
}
</style>
