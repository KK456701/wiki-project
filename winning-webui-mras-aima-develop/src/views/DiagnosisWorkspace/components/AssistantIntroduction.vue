<script setup lang="ts">
import { computed } from 'vue';
import { DIAGNOSIS_ASSISTANT_ACTION } from '@/constants/diagnosis';
import TroubleshootingCaseCategories from '@/views/DiagnosisWorkspace/components/TroubleshootingCaseCategories.vue';

const props = defineProps<{ caseId: string; mode?: string }>();

const patientMode = computed(() => props.mode === DIAGNOSIS_ASSISTANT_ACTION.PATIENT_CLARIFICATION);
const autonomousMode = computed(() => props.mode === DIAGNOSIS_ASSISTANT_ACTION.AUTONOMOUS);
</script>

<template>
  <div class="assistant-introduction">
    <div class="assistant-intro">
      <template v-if="patientMode">
        <strong class="text-label-medium font-weight-medium text-high-emphasis">
          你好，我是患者澄清助手。
        </strong>
        <p class="text-label-medium text-medium-emphasis">
          我可以帮你解释为什么统计到了或没有统计到某位患者。请在下方选择患者，我会沿当前数据链路逐层核对并说明原因。
        </p>
      </template>
      <template v-else-if="autonomousMode">
        <strong class="text-label-medium font-weight-medium text-high-emphasis">
          你好，我是 AI 自主排查助手。
        </strong>
        <p class="text-label-medium text-medium-emphasis">
          请描述你发现的指标异常。我会结合当前口径、数据链路、SQL
          与运行证据逐步核查；需要现场确认时，我会向你提问。
        </p>
      </template>
      <template v-else>
        <strong class="text-label-medium font-weight-medium text-high-emphasis">
          你好，我是 AI 排查助手。
        </strong>
        <p class="text-label-medium text-medium-emphasis">
          我可以帮你核对患者为什么被统计，引导你选择要排除的患者或科室来生成对应
          SQL，也可以启动自主排查功能。
        </p>
      </template>
    </div>
    <TroubleshootingCaseCategories v-if="!patientMode && !autonomousMode" :case-id="caseId" />
  </div>
</template>

<style lang="scss" scoped>
.assistant-intro {
  padding: 12px 14px;
  background: rgb(var(--v-theme-surface));
  border: 1px solid rgba(var(--v-border-color), var(--v-border-opacity));
  border-radius: 8px;

  p {
    margin: 4px 0 0;
  }
}

</style>
