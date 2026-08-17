<script setup lang="ts">
import { computed } from 'vue';

const props = defineProps<{
  ruleName?: string;
  profileName?: string;
  dateText?: string;
}>();

/** 副标题文本：非空字段以中点连接，作为标题下方的上下文摘要 */
const text = computed(() =>
  [props.ruleName, props.profileName, props.dateText].filter(Boolean).join(' · '),
);
</script>

<template>
  <div v-if="text" class="text-body-small text-medium-emphasis text-truncate">
    {{ text }}
  </div>
</template>
