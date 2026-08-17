<script setup lang="ts">
import { ref, computed } from 'vue';
import { useRouter } from 'vue-router';
import { format } from 'date-fns';
import type { RuleItem } from '@/services/chat';
import { DATE_SHORTCUTS, useDateShortcuts } from '../composables/useDateShortcuts';

const QUICK_ACTION_TYPE = {
  CALCULATE: 'calculate',
  TROUBLESHOOT: 'troubleshoot',
} as const;

type QuickActionType = (typeof QUICK_ACTION_TYPE)[keyof typeof QUICK_ACTION_TYPE];

const QUICK_ACTION_CARDS = [
  {
    type: QUICK_ACTION_TYPE.CALCULATE as QuickActionType,
    title: '算指标',
    desc: '多方案对比 + AI 推荐 + 数据质量报告',
    icon: 'mdi-calculator-variant-outline',
    iconActive: 'mdi-calculator-variant',
    buttonText: '开始计算',
    buttonIcon: 'mdi-play-circle',
    buttonColor: 'primary',
    multipleRule: true,
  },
  {
    type: QUICK_ACTION_TYPE.TROUBLESHOOT as QuickActionType,
    title: '查故障',
    desc: 'AI 排查计算链路，定位数据/口径问题',
    icon: 'mdi-magnify',
    iconActive: 'mdi-magnify-scan',
    buttonText: '开始排查',
    buttonIcon: 'mdi-magnify-scan',
    buttonColor: 'warning',
    multipleRule: false,
  },
] as const;

const emit = defineEmits<{ select: [text: string]; error: [message: string] }>();

const props = defineProps<{ rules: RuleItem[] }>();

const router = useRouter();

const selectedCard = ref<QuickActionType | null>(null);
const _selectedRuleIds = ref<string[]>([]);
const _dateRange = ref<Date[]>([]);

const { setDateRange } = useDateShortcuts(_dateRange);

const selectedRuleIds = computed<string[]>({
  get: () => _selectedRuleIds.value,
  set: (val) => {
    _selectedRuleIds.value = val ?? [];
  },
});
const dateRange = computed<Date[]>({
  get: () => _dateRange.value,
  set: (val) => {
    _dateRange.value = val ?? [];
  },
});

const allRuleIds = computed(() => [...new Set(props.rules.map((r) => r.ruleId))]);

const isAllSelected = computed(
  () =>
    allRuleIds.value.length > 0 &&
    allRuleIds.value.every((id) => selectedRuleIds.value.includes(id)),
);

const isIndeterminate = computed(
  () => selectedRuleIds.value.length > 0 && selectedRuleIds.value.length < allRuleIds.value.length,
);

/** 仅「算指标」使用内联表单；「查故障」直接跳转排查工作区 */
const showForm = computed(() => selectedCard.value === QUICK_ACTION_TYPE.CALCULATE);

const activeCard = computed(
  () => QUICK_ACTION_CARDS.find((c) => c.type === selectedCard.value) ?? null,
);

const buttonEnabled = computed(
  () => !!selectedCard.value && selectedRuleIds.value.length > 0 && dateRange.value.length >= 2,
);

function toggleCard(type: QuickActionType) {
  selectedRuleIds.value = [];
  selectedCard.value = selectedCard.value === type ? null : type;
}

function onCardClick(type: QuickActionType) {
  if (type === QUICK_ACTION_TYPE.TROUBLESHOOT) {
    // 查故障：弹出全屏排查工作区（独立路由，刷新可恢复）
    router.push({ path: '/diagnosis', query: { step: 'selection' } });
    return;
  }
  toggleCard(type);
}

function toggleSelectAll() {
  const selectAll = !isAllSelected.value;
  selectedRuleIds.value = selectAll ? [...allRuleIds.value] : [];
}

function removeSelectedRule(ruleId: string) {
  selectedRuleIds.value = selectedRuleIds.value.filter((id) => id !== ruleId);
}

function formatDate(d: Date | null): string {
  return d ? format(d, 'yyyy-MM-dd') : '';
}

function buildPrompt(isCalc: boolean): string {
  // 即使用户点击“全选”也发送当时真实选中的名称，避免目录变化或控件状态
  // 把单指标错误压缩成“全部指标”，让后端执行超出用户选择范围的任务。
  const selectedNames = props.rules
    .filter((r) => selectedRuleIds.value.includes(r.ruleId))
    .map((r) => r.ruleName)
    .join('、');
  const dateRangeStr = `${formatDate(dateRange.value[0])} 至 ${formatDate(dateRange.value[1])}`;
  return isCalc
    ? `请用医院真实数据试算以下指标的全部公版口径：${selectedNames}，统计周期：${dateRangeStr}，输出多方案对比 + AI 推荐 + 数据质量报告`
    : `请排查以下指标的计算链路：${selectedNames}，统计周期：${dateRangeStr}，定位数据/口径问题并给出建议`;
}

function handleSubmit() {
  if (!buttonEnabled.value) return;
  emit('select', buildPrompt(true));
}
</script>

<template>
  <div class="quick-actions w-100 mx-auto">
    <div class="d-flex flex-wrap justify-center ga-4 mb-4">
      <v-card
        v-for="card in QUICK_ACTION_CARDS"
        :key="card.type"
        :color="selectedCard === card.type ? 'primary' : undefined"
        :variant="selectedCard === card.type ? 'tonal' : 'outlined'"
        :class="['quick-action-card', { 'quick-action-card--active': selectedCard === card.type }]"
        @click="onCardClick(card.type)"
      >
        <v-card-item>
          <div class="d-flex align-center ga-3">
            <v-icon
              :icon="selectedCard === card.type ? card.iconActive : card.icon"
              size="28"
              :color="selectedCard === card.type ? 'primary' : undefined"
            />
            <div>
              <div class="text-body-large font-weight-medium">{{ card.title }}</div>
              <div class="text-body-medium text-medium-emphasis">{{ card.desc }}</div>
            </div>
          </div>
        </v-card-item>
      </v-card>
    </div>

    <v-expand-transition>
      <div v-if="showForm" class="quick-action-form mb-4">
        <v-autocomplete
          v-model="selectedRuleIds"
          :items="rules"
          :multiple="activeCard?.multipleRule ?? false"
          item-title="ruleName"
          item-value="ruleId"
          :label="activeCard?.multipleRule ? '选择指标（可多选）' : '选择指标'"
          prepend-icon="mdi-chart-bar"
          chips
          clearable
          hide-details
          class="mb-3"
        >
          <template v-if="activeCard?.multipleRule" #chip="{ item, index }">
            <v-chip
              v-if="index === 0"
              closable
              size="small"
              @click:close="removeSelectedRule(item.ruleId)"
            >
              {{ item.ruleName }}
            </v-chip>
            <span
              v-else-if="index === 1"
              class="text-body-medium font-weight-medium text-primary ml-1"
              >+{{ selectedRuleIds.length - 1 }}</span
            >
          </template>
          <template v-if="activeCard?.multipleRule" #prepend-item>
            <div class="select-all-header">
              <v-list-item :title="isAllSelected ? '取消全选' : '全选'" @click="toggleSelectAll">
                <template #prepend>
                  <v-checkbox-btn
                    :model-value="isAllSelected"
                    :indeterminate="isIndeterminate"
                    @click.stop="toggleSelectAll"
                  />
                </template>
              </v-list-item>
              <v-divider />
            </div>
          </template>
        </v-autocomplete>

        <v-date-input
          v-model="dateRange"
          multiple="range"
          label="选择日期范围"
          hide-details
          clearable
          class="mb-1"
        />

        <div class="d-flex ga-2 mb-3 pl-10">
          <v-chip
            v-for="sc in DATE_SHORTCUTS"
            :key="sc.key"
            size="small"
            label
            density="comfortable"
            @click="setDateRange(sc.key)"
          >
            {{ sc.label }}
          </v-chip>
        </div>

        <v-btn
          :color="activeCard?.buttonColor ?? 'primary'"
          variant="flat"
          block
          size="large"
          :disabled="!buttonEnabled"
          @click="handleSubmit"
        >
          <v-icon start :icon="activeCard?.buttonIcon ?? 'mdi-play-circle'" />
          {{ activeCard?.buttonText ?? '' }}
        </v-btn>
      </div>
    </v-expand-transition>
  </div>
</template>

<style lang="scss" scoped src="./styles/_quick-actions.scss"></style>
