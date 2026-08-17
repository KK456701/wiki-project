<script setup lang="ts">
/** 明细弹窗筛选栏——纯展示组件 */
defineProps<{
  filterName: string;
  filterEncounter: string;
  filterDept: string;
  departmentOptions: string[];
  allPageSelected: boolean;
  /** 任务已结束时只读 */
  readonly?: boolean;
}>();

const emit = defineEmits<{
  'update:filterName': [value: string];
  'update:filterEncounter': [value: string];
  'update:filterDept': [value: string];
  reset: [];
  'toggle-all': [];
}>();

function onFilterName(v: string) {
  emit('update:filterName', v);
}
function onFilterEncounter(v: string) {
  emit('update:filterEncounter', v);
}
function onFilterDept(v: string) {
  emit('update:filterDept', v);
}
</script>

<template>
  <div class="d-flex flex-wrap ga-2 mt-2 align-end">
    <v-text-field
      :model-value="filterName"
      label="患者姓名"
      density="compact"
      variant="outlined"
      hide-details
      clearable
      style="max-width: 160px"
      @update:model-value="onFilterName"
    />
    <v-text-field
      :model-value="filterEncounter"
      label="就诊号/住院号"
      density="compact"
      variant="outlined"
      hide-details
      clearable
      style="max-width: 180px"
      @update:model-value="onFilterEncounter"
    />
    <v-select
      :model-value="filterDept"
      :items="departmentOptions"
      label="科室"
      density="compact"
      variant="outlined"
      hide-details
      clearable
      style="max-width: 180px"
      @update:model-value="onFilterDept"
    />
    <v-btn size="small" variant="text" @click="emit('reset')">重置</v-btn>
    <v-spacer />
    <v-btn
      size="x-small"
      variant="text"
      :color="allPageSelected ? 'error' : 'primary'"
      :disabled="readonly"
      @click="emit('toggle-all')"
    >
      {{ allPageSelected ? '取消本页全选' : '本页全选' }}
    </v-btn>
  </div>
</template>
