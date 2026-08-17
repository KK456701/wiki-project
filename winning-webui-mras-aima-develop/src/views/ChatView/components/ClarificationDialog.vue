<script setup lang="ts">
import { ref, computed } from 'vue';
import type { Clarification } from '@/types/chat';

const props = defineProps<{
  modelValue: boolean;
  clarification: Clarification | null;
}>();

const emit = defineEmits<{
  'update:modelValue': [value: boolean];
  confirm: [selectedValues: string[]];
}>();

const selectedOptions = ref<string[]>([]);
const singleSelectedValue = ref<string | null>(null);
const freeText = ref('');
const filterText = ref('');

const isSingleSelect = computed(() => props.clarification?.selectionMode === 'single');

/** 总选项数超过 8 时显示筛选输入框（文档 §6.5） */
const showFilter = computed(() => {
  if (!props.clarification) return false;
  return props.clarification.options.length > 8;
});

const groupedOptions = computed(() => {
  if (!props.clarification) return [];
  const keyword = filterText.value.toLowerCase();
  const groups = new Map<string, typeof props.clarification.options>();
  for (const option of props.clarification.options) {
    // 本地筛选
    if (
      keyword &&
      !option.label.toLowerCase().includes(keyword) &&
      !option.description.toLowerCase().includes(keyword)
    ) {
      continue;
    }
    const group = option.group || '其他';
    if (!groups.has(group)) {
      groups.set(group, []);
    }
    groups.get(group)!.push(option);
  }
  return Array.from(groups.entries());
});

/**
 * 多选模式下 toggle 选项（文档 §6.5：indicator:all 与其他选项互斥）
 */
function toggleMultiOption(value: string) {
  if (value === 'indicator:all') {
    // indicator:all 互斥：选中时清空其他，取消时清空全部
    selectedOptions.value = selectedOptions.value.includes('indicator:all')
      ? []
      : ['indicator:all'];
  } else {
    // 选中其他选项时移除 indicator:all
    selectedOptions.value = selectedOptions.value.filter((v) => v !== 'indicator:all');
    const idx = selectedOptions.value.indexOf(value);
    if (idx >= 0) {
      selectedOptions.value.splice(idx, 1);
    } else {
      selectedOptions.value.push(value);
    }
  }
}

function handleConfirm() {
  let values: string[];

  if (isSingleSelect.value) {
    values = singleSelectedValue.value ? [singleSelectedValue.value] : [];
  } else {
    values = selectedOptions.value.length > 0 ? selectedOptions.value : [];
  }

  if (values.length === 0 && freeText.value) {
    values = [freeText.value];
  }

  if (values.length === 0) return;

  emit('confirm', values);
  emit('update:modelValue', false);
  selectedOptions.value = [];
  singleSelectedValue.value = null;
  freeText.value = '';
  filterText.value = '';
}

function handleClose() {
  emit('update:modelValue', false);
  selectedOptions.value = [];
  singleSelectedValue.value = null;
  freeText.value = '';
  filterText.value = '';
}
</script>

<template>
  <v-dialog :model-value="modelValue" max-width="560" persistent @update:model-value="handleClose">
    <v-card v-if="clarification" class="d-flex flex-column" style="max-height: 80vh">
      <v-card-title class="d-flex align-center flex-shrink-0">
        <v-icon icon="mdi-help-circle-outline" color="primary" class="mr-2" />
        {{ clarification.title }}
      </v-card-title>

      <v-card-text class="flex-grow-1" style="overflow-y: auto">
        <p class="text-body-large mb-4">{{ clarification.question }}</p>
        <p v-if="clarification.helpText" class="text-body-small text-medium-emphasis mb-4">
          {{ clarification.helpText }}
        </p>

        <!-- 筛选输入框（选项超过 8 个时显示） -->
        <v-text-field
          v-if="showFilter"
          v-model="filterText"
          placeholder="筛选选项..."
          variant="outlined"
          density="compact"
          hide-details
          clearable
          prepend-inner-icon="mdi-magnify"
          class="mb-3"
        />

        <!-- 选项列表 -->
        <div v-for="[group, options] in groupedOptions" :key="group" class="mb-3">
          <div v-if="groupedOptions.length > 1" class="text-body-small text-medium-emphasis mb-1">
            {{ group }}
          </div>
          <v-radio-group
            v-if="isSingleSelect"
            v-model="singleSelectedValue"
            hide-details
            class="mt-0"
          >
            <v-radio
              v-for="option in options"
              :key="option.id"
              :label="option.label"
              :value="option.value"
            >
              <template #label>
                <div>
                  <div class="text-body-medium">{{ option.label }}</div>
                  <div v-if="option.description" class="text-body-small text-medium-emphasis">
                    {{ option.description }}
                  </div>
                </div>
              </template>
            </v-radio>
          </v-radio-group>

          <div v-else>
            <v-checkbox
              v-for="option in options"
              :key="option.id"
              :model-value="selectedOptions.includes(option.value)"
              :label="option.label"
              :value="option.value"
              hide-details
              density="compact"
              @update:model-value="toggleMultiOption(option.value)"
            />
          </div>
        </div>

        <!-- 自由输入 -->
        <v-textarea
          v-if="clarification.allowFreeText"
          v-model="freeText"
          :placeholder="clarification.freeTextPlaceholder || '或输入自定义内容...'"
          variant="outlined"
          density="compact"
          rows="2"
          hide-details
          class="mt-2"
        />
      </v-card-text>

      <v-card-actions class="flex-shrink-0">
        <v-spacer />
        <v-btn variant="text" @click="handleClose">取消</v-btn>
        <v-btn
          color="primary"
          variant="flat"
          :disabled="
            (isSingleSelect ? !singleSelectedValue : selectedOptions.length === 0) &&
            !freeText.trim()
          "
          @click="handleConfirm"
        >
          确认
        </v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
