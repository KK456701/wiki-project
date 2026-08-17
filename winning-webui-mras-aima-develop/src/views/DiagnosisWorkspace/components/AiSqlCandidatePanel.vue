<script setup lang="ts">
import { computed, ref } from 'vue';
import { useClipboard } from '@vueuse/core';
import type { DiagnosisCaseSnapshot } from '@/types/diagnosis';
import DiagnosisSqlExecuteButton from './DiagnosisSqlExecuteButton.vue';

const props = defineProps<{ snapshot: DiagnosisCaseSnapshot | null; nodeId?: string }>();

function record(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {};
}

const candidateRoot = computed(() => record(props.snapshot?.candidateSql));
const candidate = computed(() => {
  const changes = Array.isArray(candidateRoot.value.changes) ? candidateRoot.value.changes : [];
  const matched = changes.find((value) => record(value).nodeId === props.nodeId);
  return matched ? { ...candidateRoot.value, ...record(matched) } : candidateRoot.value;
});
const candidateExecutable = computed(() =>
  String(
    candidate.value.candidateSqlExecutable ||
      candidate.value.candidateSql ||
      candidate.value.sql ||
      '',
  ),
);
const candidateOriginal = computed(() =>
  String(candidate.value.originalSqlExecutable || candidate.value.originalSql || ''),
);
const hasCandidate = computed(() => candidateExecutable.value.trim().length > 0);
const validationStages = computed(() =>
  Array.isArray(candidate.value.validationStages)
    ? candidate.value.validationStages.map(String)
    : [],
);
const validationMessage = computed(() =>
  String(record(candidate.value.validation).message || '安全校验已通过'),
);

type DiffLine = { kind: 'same' | 'added' | 'removed'; text: string };

function buildSqlDiff(original: string, current: string): DiffLine[] {
  const before = original.replace(/\r\n/g, '\n').split('\n');
  const after = current.replace(/\r\n/g, '\n').split('\n');
  if (!original) return after.map((text) => ({ kind: 'added', text }));
  if (!current) return before.map((text) => ({ kind: 'removed', text }));
  if (before.length * after.length > 500_000) {
    const originalLines = new Set(before);
    return after.map((text) => ({
      kind: originalLines.has(text) ? 'same' : 'added',
      text,
    }));
  }
  const matrix = Array.from({ length: before.length + 1 }, () => new Uint32Array(after.length + 1));
  for (let i = before.length - 1; i >= 0; i -= 1) {
    for (let j = after.length - 1; j >= 0; j -= 1) {
      matrix[i][j] =
        before[i] === after[j]
          ? matrix[i + 1][j + 1] + 1
          : Math.max(matrix[i + 1][j], matrix[i][j + 1]);
    }
  }
  const result: DiffLine[] = [];
  let i = 0;
  let j = 0;
  while (i < before.length || j < after.length) {
    if (i < before.length && j < after.length && before[i] === after[j]) {
      result.push({ kind: 'same', text: before[i] });
      i += 1;
      j += 1;
    } else if (j < after.length && (i >= before.length || matrix[i][j + 1] >= matrix[i + 1][j])) {
      result.push({ kind: 'added', text: after[j] });
      j += 1;
    } else {
      result.push({ kind: 'removed', text: before[i] });
      i += 1;
    }
  }
  return result;
}

const diffLines = computed(() => buildSqlDiff(candidateOriginal.value, candidateExecutable.value));
const changedLines = computed(() => diffLines.value.filter((line) => line.kind !== 'same'));
const validationExpanded = ref(false);
const sqlExpanded = ref(false);
const { copy, copied } = useClipboard({ legacy: true });

async function copyCandidate() {
  if (candidateExecutable.value) await copy(candidateExecutable.value);
}
</script>

<template>
  <div v-if="snapshot && hasCandidate" class="candidate-panel mt-3">
    <v-divider class="candidate-divider mb-3" />
    <div class="candidate-toolbar d-flex align-center ga-2 mb-2">
      <v-icon icon="mdi-database-search-outline" size="small" color="primary" />
      <span class="text-label-large">候选 SQL 脚本</span>
      <v-btn
        size="x-small"
        variant="text"
        class="text-label-large"
        :append-icon="sqlExpanded ? 'mdi-chevron-up' : 'mdi-chevron-down'"
        @click="sqlExpanded = !sqlExpanded"
      >
        {{ sqlExpanded ? '收起' : '展开' }}
      </v-btn>
      <v-btn
        v-if="validationStages.length"
        size="x-small"
        density="compact"
        variant="text"
        prepend-icon="mdi-shield-check-outline"
        :append-icon="validationExpanded ? 'mdi-chevron-up' : 'mdi-chevron-down'"
        class="text-label-large"
        @click="validationExpanded = !validationExpanded"
      >
        安全校验 {{ validationStages.length }} 项已通过
      </v-btn>
      <v-spacer />
      <DiagnosisSqlExecuteButton
        :sql="candidateExecutable"
        :role-hint="String(candidate.layer ?? '')"
        :node-kind="String(candidate.sqlKind ?? candidate.layer ?? nodeId ?? '')"
        :snapshot="snapshot"
      />
      <v-btn
        variant="text"
        size="x-small"
        :color="copied ? 'success' : undefined"
        :prepend-icon="copied ? 'mdi-check-circle-outline' : 'mdi-content-copy'"
        class="text-label-large"
        @click="copyCandidate"
      >
        {{ copied ? '已复制' : '复制 SQL' }}
      </v-btn>
    </div>

    <v-expand-transition>
      <div v-if="validationExpanded" class="candidate-validation mb-2">
        <div
          v-for="stage in validationStages"
          :key="stage"
          class="d-flex align-center text-body-small"
        >
          <v-icon icon="mdi-check-circle-outline" size="x-small" color="success" class="mr-1" />
          <span class="text-medium-emphasis">{{ stage }}</span>
        </div>
      </div>
    </v-expand-transition>
    <div v-if="!validationStages.length" class="text-body-small text-medium-emphasis mb-2">
      {{ validationMessage }}
    </div>

    <div class="text-body-small text-medium-emphasis mb-2">
      本次 SQL 脚本变动 <strong>{{ changedLines.length }} 行</strong>
    </div>
    <div class="diff-box pa-2 rounded mb-2">
      <code
        v-for="(line, index) in changedLines.slice(0, 10)"
        :key="index"
        :class="`diff-line diff-${line.kind}`"
        ><span class="text-body-small mr-1">{{ line.kind === 'added' ? '+' : '−' }}</span
        >{{ line.text }}</code
      >
      <div v-if="changedLines.length > 10" class="text-body-small text-medium-emphasis">
        …还有 {{ changedLines.length - 10 }} 行
      </div>
    </div>

    <pre v-if="sqlExpanded" class="sql-full mt-2"><code
      v-for="(line, index) in diffLines"
      :key="index"
      :class="`diff-line diff-${line.kind}`"
    ><span class="text-body-small mr-2">{{ line.kind === 'added' ? '+' : line.kind === 'removed' ? '−' : ' ' }}</span>{{ line.text }}</code></pre>
  </div>
</template>

<style lang="scss" scoped>
.diff-box,
.sql-full {
  background: rgba(var(--v-theme-on-surface), 0.03);
  overflow-y: auto;
}

.candidate-divider {
  border-color: #000;
  opacity: 1;
}

.diff-box {
  max-height: 200px;
}

.sql-full {
  max-height: 400px;
  padding: 8px;
  border-radius: 4px;
  white-space: pre-wrap;
  word-break: break-all;
}

.diff-line {
  display: block;
  font:
    11px/1.5 'Roboto Mono',
    'Courier New',
    monospace;
}

.diff-added {
  color: rgb(var(--v-theme-success));
}
.diff-removed {
  color: rgb(var(--v-theme-error));
}
</style>
