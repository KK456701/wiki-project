<script setup lang="ts">
/* eslint no-console: "off" */
import { ref, onErrorCaptured } from 'vue';
import { monitorSDK } from '../index';
import { safeExecutor } from '../core/safe-executor';
import { ERROR_TYPE } from '../constants';

/**
 * Vue 3 错误边界组件
 *
 * @description 捕获子组件渲染/生命周期中的错误，上报到 MonitorSDK，
 * 同时展示降级 UI 防止白屏。错误不会继续向上传播。
 *
 * @example
 * ```vue
 * <ErrorBoundary>
 *   <template #default>
 *     <YourComponent />
 *   </template>
 *   <template #fallback="{ error }">
 *     <div>出错了：{{ error.message }}</div>
 *   </template>
 * </ErrorBoundary>
 * ```
 */
const hasError = ref(false);
const errorInfo = ref<Error | null>(null);

function handleRefresh() {
  window.location.reload();
}

onErrorCaptured((error, instance, info) => {
  hasError.value = true;
  errorInfo.value = error;

  // 无论 MonitorSDK 是否启用，始终输出到控制台，方便排查问题
  console.error(
    `[ErrorBoundary] ${error instanceof Error ? error.message : String(error)}`,
    error instanceof Error ? (error.stack ?? '') : '',
    { component: instance?.$.type?.__name ?? 'Unknown', info },
  );

  // 上报到 MonitorSDK
  safeExecutor.run(() => {
    monitorSDK.report({
      type: ERROR_TYPE.VUE_ERROR,
      message: error instanceof Error ? error.message : String(error),
      stack: error instanceof Error ? (error.stack ?? '') : '',
      timestamp: Date.now(),
      url: location.href,
      extra: {
        componentName: instance?.$.type?.__name ?? 'Unknown',
        hookInfo: info,
      },
    });
  }, 'error-boundary');

  // 返回 false 阻止错误继续向上传播
  return false;
});
</script>

<template>
  <slot v-if="!hasError" name="default" />
  <slot v-else name="fallback" :error="errorInfo">
    <div class="d-flex items-center justify-center h-100 p-8">
      <v-card
        max-width="480"
        class="w-100 text-center pa-8 align-self-center"
        variant="flat"
        rounded="lg"
      >
        <v-icon icon="mdi-alert-circle-outline" size="64" color="error" class="mb-4" />

        <h2 class="text-headline-small font-weight-bold mb-2">页面发生错误</h2>

        <p v-if="errorInfo" class="text-body-medium text-medium-emphasis mb-4 px-4">
          {{ errorInfo.message }}
        </p>

        <p class="text-body-small text-disabled mb-6">请尝试刷新页面，如问题持续请联系技术支持</p>

        <v-btn color="primary" variant="flat" rounded="pill" @click="handleRefresh">
          <v-icon icon="mdi-refresh" start />
          刷新页面
        </v-btn>
      </v-card>
    </div>
  </slot>
</template>
