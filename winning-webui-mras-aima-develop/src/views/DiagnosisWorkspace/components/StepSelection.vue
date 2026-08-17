<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import {
  format,
  startOfMonth,
  endOfMonth,
  subMonths,
  startOfYear,
  endOfYear,
  subYears,
} from 'date-fns';
import type { RuleItem } from '@/services/chat';
import type { RuleProfile } from '@/types/chat';
import type { SelectionPrefill } from '@/views/DiagnosisWorkspace/composables/useDiagnosisWorkspace';

const props = defineProps<{
  creating: boolean;
  /** 指标列表：由页面级 composable 加载并缓存，整页仅查询一次 */
  rules: RuleItem[];
  rulesLoading: boolean;
  /** 按指标取口径：命中页面级缓存直接返回，否则请求并写入缓存 */
  getProfiles: (ruleId: string) => Promise<RuleProfile[]>;
  prefill?: SelectionPrefill | null;
  candidateRuleIds?: string[];
  /** 任务已结束时整步只读 */
  readonly?: boolean;
}>();

const emit = defineEmits<{
  submit: [payload: { ruleId: string; profileId: string; statStart: string; statEnd: string }];
  summary: [info: { ruleName: string; profileName: string; dateText: string }];
}>();

const selectedRuleId = ref<string | null>(null);
const ruleItemTitle = (rule: RuleItem) => `${rule.ruleName} · ${rule.ruleId}`;
const visibleRules = computed(() => {
  if (!props.candidateRuleIds?.length) return props.rules;
  const allowed = new Set(props.candidateRuleIds);
  return props.rules.filter((rule) => allowed.has(rule.ruleId));
});

const profiles = ref<RuleProfile[]>([]);
const profilesLoading = ref(false);
const selectedProfileId = ref<string | null>(null);

const dateRange = ref<Date[]>([]);

/** 快捷统计周期（沿用原 GuidedTaskPanel 的预设项） */
const datePresets = ['本月', '上月', '近三个月', '近半年', '今年以来', '去年全年'] as const;
type DatePreset = (typeof datePresets)[number];

function presetRange(kind: DatePreset): [Date, Date] {
  const now = new Date();
  switch (kind) {
    case '本月':
      return [startOfMonth(now), now];
    case '上月': {
      const m = subMonths(now, 1);
      return [startOfMonth(m), endOfMonth(m)];
    }
    case '近三个月':
      return [subMonths(startOfMonth(now), 2), now];
    case '近半年':
      return [subMonths(now, 6), now];
    case '今年以来':
      return [startOfYear(now), now];
    case '去年全年': {
      const y = subYears(now, 1);
      return [startOfYear(y), endOfYear(y)];
    }
  }
}

function applyPreset(p: DatePreset) {
  dateRange.value = presetRange(p);
}

const selectedRule = computed(
  () => props.rules.find((r) => r.ruleId === selectedRuleId.value) ?? null,
);
const selectedProfile = computed(
  () => profiles.value.find((pr) => pr.profileId === selectedProfileId.value) ?? null,
);

const dateText = computed(() => {
  const [s, e] = dateRange.value;
  if (!s || !e) return '';
  return `${format(s, 'yyyy-MM-dd')} ~ ${format(e, 'yyyy-MM-dd')}`;
});

const canSubmit = computed(
  () =>
    !!selectedRuleId.value &&
    !!selectedProfileId.value &&
    dateRange.value.length >= 2 &&
    !props.creating,
);

// 选择指标 → 加载口径（优先页面级缓存）；若仅一个口径则自动选中
watch(selectedRuleId, async (ruleId) => {
  selectedProfileId.value = null;
  profiles.value = [];
  if (!ruleId) return;
  profilesLoading.value = true;
  try {
    const list = await props.getProfiles(ruleId);
    profiles.value = list;
    if (list.length === 1) selectedProfileId.value = list[0].profileId;
  } catch {
    profiles.value = [];
  } finally {
    profilesLoading.value = false;
  }
});

// 已有案例回填：指标 + 统计周期先填，口径待 profiles 加载后回填
const prefillApplied = ref(false);
function applyPrefill() {
  if (prefillApplied.value || !props.prefill) return;
  const p = props.prefill;
  if (p.statStart && p.statEnd) {
    const s = new Date(p.statStart);
    const e = new Date(p.statEnd);
    if (!Number.isNaN(s.getTime()) && !Number.isNaN(e.getTime())) dateRange.value = [s, e];
  }
  if (p.ruleId) selectedRuleId.value = p.ruleId;
  prefillApplied.value = true;
}
// 父组件 onMounted 中 loadCase 是异步的：子组件挂载时 prefill 尚为 null，
// 案例加载完 prefill 才有值，需监听其变化以触发回填（onMounted 内的调用仅覆盖同步命中场景）
watch(
  () => props.prefill,
  (p) => {
    if (p) applyPrefill();
  },
);
watch(profiles, () => {
  if (!props.prefill) return;
  if (selectedRuleId.value !== props.prefill.ruleId || selectedProfileId.value) return;
  if (
    props.prefill.profileId &&
    profiles.value.some((pr) => pr.profileId === props.prefill!.profileId)
  ) {
    selectedProfileId.value = props.prefill!.profileId;
  }
});

// 同步摘要信息给头部
watch([selectedRule, selectedProfile, dateText], () => {
  if (selectedRule.value && selectedProfile.value && dateText.value) {
    emit('summary', {
      ruleName: selectedRule.value.ruleName,
      profileName: selectedProfile.value.profileName || selectedProfile.value.label,
      dateText: dateText.value,
    });
  }
});

function formatApi(d: Date, isEnd = false): string {
  return `${format(d, 'yyyy-MM-dd')}T${isEnd ? '23:59:59' : '00:00:00'}`;
}

function submit() {
  if (!selectedRuleId.value || !selectedProfileId.value || dateRange.value.length < 2) return;
  emit('submit', {
    ruleId: selectedRuleId.value,
    profileId: selectedProfileId.value,
    statStart: formatApi(dateRange.value[0]),
    statEnd: formatApi(dateRange.value[1], true),
  });
}

onMounted(() => {
  // 指标列表由页面级 composable 在 onMounted 加载，此处直接应用回填（异步命中由 watch(prefill) 兜底）
  applyPrefill();
});
</script>

<template>
  <div class="dw-selection mx-auto" style="max-width: 720px">
    <v-card variant="outlined" class="pa-5">
      <div class="text-body-large font-weight-medium mb-1">选择指标与口径</div>
      <div class="text-body-medium text-medium-emphasis mb-4">
        选定指标、对应口径与统计周期后开始排查。三项均为必填。
      </div>

      <v-autocomplete
        v-model="selectedRuleId"
        :items="visibleRules"
        :item-title="ruleItemTitle"
        item-value="ruleId"
        label="指标（单选）"
        prepend-icon="mdi-chart-bar"
        :loading="rulesLoading"
        :disabled="readonly"
        clearable
        hide-details
        class="mb-4"
      />

      <v-autocomplete
        v-model="selectedProfileId"
        :items="profiles"
        item-title="profileName"
        item-value="profileId"
        label="口径（单选）"
        prepend-icon="mdi-tune"
        :loading="profilesLoading"
        :disabled="readonly || !selectedRuleId"
        :hint="profiles.length === 1 ? '仅有一个口径，已自动选中' : undefined"
        persistent-hint
        clearable
        hide-details="auto"
        class="mb-4"
      />

      <v-date-input
        v-model="dateRange"
        multiple="range"
        label="统计周期（也可手动选择）"
        prepend-icon="mdi-calendar-range"
        :disabled="readonly"
        hide-details
        clearable
        class="mb-5"
      />
      <div class="d-flex flex-wrap ga-2 ml-10 mb-4">
        <v-chip
          v-for="p in datePresets"
          :key="p"
          size="x-small"
          :disabled="readonly"
          @click="applyPreset(p)"
        >
          {{ p }}
        </v-chip>
      </div>

      <v-btn
        color="warning"
        variant="flat"
        size="large"
        block
        :loading="creating"
        :disabled="readonly || !canSubmit"
        prepend-icon="mdi-magnify-scan"
        @click="submit"
      >
        开始排查
      </v-btn>
    </v-card>
  </div>
</template>
