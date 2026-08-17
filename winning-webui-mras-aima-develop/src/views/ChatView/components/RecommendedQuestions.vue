<script setup lang="ts">
import type { RecommendedQuestion } from '../constants';

const emit = defineEmits<{
  select: [text: string];
}>();

defineProps<{
  questions: RecommendedQuestion[];
}>();

function handleClick(text: string) {
  emit('select', text);
}
</script>

<template>
  <div class="recommended-questions w-100 mx-auto">
    <p class="text-body-medium text-medium-emphasis mb-3">您可以尝试以下问题：</p>
    <div class="d-flex flex-wrap justify-center ga-2">
      <v-chip v-for="question in questions" :key="question.id" @click="handleClick(question.text)">
        {{ question.text }}
      </v-chip>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.recommended-questions {
  max-width: 600px;
}
</style>
