<script setup lang="ts">
defineProps<{
  message: string;
  thinking: boolean;
  stopped?: boolean;
}>();
</script>

<template>
  <div class="assistant-conversation mb-3">
    <div class="assistant-message is-user mb-3">
      <p class="text-body-medium">{{ message }}</p>
    </div>
    <div v-if="thinking" class="assistant-verification" aria-live="polite">
      <v-progress-circular indeterminate color="primary" size="18" width="2" />
      <div>
        <strong class="text-title-small font-weight-medium">正在核验患者统计情况</strong>
        <p class="text-body-small">依次查询当前分子、分母明细并核对生效口径。</p>
      </div>
    </div>
    <div v-else-if="stopped" class="assistant-message is-assistant">
      <p class="text-body-medium">已停止本次患者澄清。</p>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.assistant-conversation {
  background: transparent;
}

.assistant-message {
  max-width: 88%;
  padding: 10px 12px;
  border: 1px solid rgba(var(--v-border-color), var(--v-border-opacity));
  border-radius: 6px;

  &.is-user {
    margin-left: auto;
    background: rgba(var(--v-theme-primary), 0.07);
    border-color: rgba(var(--v-theme-primary), 0.24);
  }

  &.is-assistant {
    background: rgb(var(--v-theme-surface));
  }

  p {
    margin: 0;
    white-space: pre-wrap;
  }
}

.assistant-verification {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  max-width: 88%;
  padding: 10px 12px;
  color: rgba(var(--v-theme-on-surface), 0.78);

  p {
    margin: 2px 0 0;
    color: rgba(var(--v-theme-on-surface), 0.64);
  }
}
</style>
