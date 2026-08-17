<script setup lang="ts">
/**
 * 应用根组件
 * @description 全局布局容器，主题切换逻辑由 settingsStore 初始化时自动处理
 */
import { onMounted } from 'vue';
import { useChatStore } from '@/stores/chat';
import ErrorBoundary from '@/monitor/components/ErrorBoundary.vue';

const chatStore = useChatStore();

onMounted(async () => {
  // 全局加载模型列表并选中默认模型，确保从任意路由进入系统均可正常使用
  await chatStore.loadModels();
});
</script>

<template>
  <v-app>
    <!--
      ErrorBoundary 捕获子组件（router-view）中的渲染/生命周期错误，
      展示降级 UI 防止整个应用白屏，同时自动上报到 MonitorSDK。
      错误不会继续向上传播到 window.onerror。
    -->
    <ErrorBoundary>
      <template #default>
        <router-view />
      </template>
    </ErrorBoundary>
  </v-app>
</template>
