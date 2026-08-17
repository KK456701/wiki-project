<script setup lang="ts">
import winexLogoSvg from '@/assets/winex-text.svg?raw';

defineProps<{ title: string; dark: boolean }>();
defineEmits<{
  toggleDrawer: [];
  toggleTheme: [];
  toggleAnnotations: [];
  openSettings: [];
  createSession: [];
}>();
</script>

<template>
  <v-app-bar elevation="1">
    <v-app-bar-nav-icon @click="$emit('toggleDrawer')" />
    <!-- eslint-disable-next-line vue/no-v-html -- trusted local SVG asset -->
    <div class="header-logo ml-1 mr-2" v-html="winexLogoSvg" />
    <v-app-bar-title>{{ title }}</v-app-bar-title>
    <template #append>
      <v-btn
        :icon="dark ? 'mdi-weather-sunny' : 'mdi-weather-night'"
        variant="text"
        :title="dark ? '切换亮色主题' : '切换暗色主题'"
        @click="$emit('toggleTheme')"
      />
      <v-btn
        icon="mdi-information-outline"
        variant="text"
        title="标注说明"
        @click="$emit('toggleAnnotations')"
      />
      <v-btn
        icon="mdi-cog-outline"
        variant="text"
        title="系统设置"
        @click="$emit('openSettings')"
      />
      <v-btn
        icon="mdi-chat-plus-outline"
        color="primary"
        variant="text"
        title="新对话"
        @click="$emit('createSession')"
      />
    </template>
  </v-app-bar>
</template>

<style scoped>
.header-logo {
  display: flex;
  width: 72px;
  color: rgb(var(--v-theme-primary));
}
.header-logo :deep(svg) {
  width: 100%;
  height: auto;
}
</style>
