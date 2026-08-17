<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useClipboard } from '@vueuse/core';
import { useDisplay } from 'vuetify';
import { format } from 'date-fns';
import type { ErrorLog } from '@/monitor/types';
import type { DetailSection, LogDetailTab } from '../types';
import { ERROR_TYPE_LABEL, ERROR_TYPE_COLOR, ERROR_TYPE_ICON } from '../constants';

const log = defineModel<ErrorLog | null>('log', { required: true });

const { mobile } = useDisplay();

const visible = computed({
  get: () => log.value !== null,
  set: (val) => {
    if (!val) log.value = null;
  },
});

const tab = ref<LogDetailTab>('detail');

// 切换日志时回到详情页签，避免上一条的页签状态残留（例如停在无堆栈的空页）
watch(log, (val) => {
  if (val) tab.value = 'detail';
});

function formatTime(ts: number): string {
  return format(ts, 'yyyy-MM-dd HH:mm:ss');
}

const jsonView = computed(() => (log.value ? JSON.stringify(log.value, null, 2) : ''));

/** 按来源分组的详情字段 */
const sections = computed<DetailSection[]>(() => {
  const item = log.value;
  if (!item) return [];

  const result: DetailSection[] = [
    {
      title: '基础信息',
      icon: 'mdi-information-outline',
      fields: [
        { label: '发生时间', value: formatTime(item.timestamp) },
        { label: '页面 URL', value: item.url, breakAll: true },
        { label: '用户标识', value: item.userId ?? '-' },
        { label: '错误信息', value: item.message, breakAll: true },
      ],
    },
  ];

  if (item.requestInfo) {
    const { method, url, status, statusText, duration } = item.requestInfo;
    result.push({
      title: 'HTTP 请求',
      icon: 'mdi-api',
      fields: [
        { label: '请求方法', value: method },
        { label: '请求 URL', value: url, breakAll: true },
        { label: '响应状态', value: `${status ?? '-'} ${statusText ?? ''}`.trim() },
        { label: '请求耗时', value: `${duration} ms` },
      ],
    });
  }

  if (item.resourceInfo) {
    const { tagName, src, outerHTML } = item.resourceInfo;
    result.push({
      title: '资源信息',
      icon: 'mdi-file-alert-outline',
      fields: [
        { label: '资源标签', value: tagName },
        { label: '资源地址', value: src, breakAll: true },
        { label: '元素 HTML', value: outerHTML, breakAll: true },
      ],
    });
  }

  if (item.pageSnapshot) {
    const { route, title, screenResolution, userAgent } = item.pageSnapshot;
    result.push({
      title: '页面快照',
      icon: 'mdi-monitor-screenshot',
      fields: [
        { label: '路由路径', value: route },
        { label: '页面标题', value: title },
        { label: '屏幕分辨率', value: screenResolution },
        { label: 'UserAgent', value: userAgent, breakAll: true },
      ],
    });
  }

  return result;
});

// copied 状态直接驱动按钮反馈，无需额外提示条
const { copy, copied, isSupported } = useClipboard({ legacy: true });
</script>

<template>
  <v-dialog
    v-model="visible"
    :max-width="720"
    :fullscreen="mobile"
    scrollable
    aria-label="错误日志详情"
  >
    <v-card v-if="log" rounded="lg">
      <v-toolbar density="comfortable" color="surface">
        <v-avatar :color="ERROR_TYPE_COLOR[log.type]" size="32" class="ml-4" variant="tonal">
          <v-icon :icon="ERROR_TYPE_ICON[log.type]" size="18" />
        </v-avatar>
        <v-toolbar-title class="text-body-large font-weight-medium">
          日志详情 #{{ log.id }}
        </v-toolbar-title>
        <v-chip size="small" variant="tonal" :color="ERROR_TYPE_COLOR[log.type]" class="mr-2">
          {{ ERROR_TYPE_LABEL[log.type] }}
        </v-chip>
        <v-btn variant="text" icon="mdi-close" aria-label="关闭" @click="visible = false" />
      </v-toolbar>

      <v-tabs v-model="tab" density="comfortable" color="primary">
        <v-tab value="detail" prepend-icon="mdi-format-list-bulleted">详情</v-tab>
        <v-tab value="stack" prepend-icon="mdi-layers-outline" :disabled="!log.stack">堆栈</v-tab>
        <v-tab value="json" prepend-icon="mdi-code-json">JSON</v-tab>
      </v-tabs>

      <v-divider />

      <v-card-text class="pa-0">
        <v-tabs-window v-model="tab">
          <!-- 详情 -->
          <v-tabs-window-item value="detail">
            <v-list lines="two" density="comfortable" class="py-0">
              <template v-for="(section, index) in sections" :key="section.title">
                <v-list-subheader class="text-body-small font-weight-medium">
                  <v-icon :icon="section.icon" size="14" class="mr-1" />
                  {{ section.title }}
                </v-list-subheader>

                <v-list-item v-for="field in section.fields" :key="field.label">
                  <v-list-item-subtitle class="text-body-small text-medium-emphasis">
                    {{ field.label }}
                  </v-list-item-subtitle>
                  <v-list-item-title
                    class="text-body-medium detail-value"
                    :class="{ 'text-break': field.breakAll }"
                  >
                    {{ field.value || '-' }}
                  </v-list-item-title>
                </v-list-item>

                <v-divider v-if="index < sections.length - 1" class="my-1" />
              </template>
            </v-list>
          </v-tabs-window-item>

          <!-- 堆栈 -->
          <v-tabs-window-item value="stack">
            <div class="pa-4">
              <pre
                v-if="log.stack"
                tabindex="0"
                class="code-block text-body-small pa-3 rounded-lg bg-surface-variant"
                >{{ log.stack }}</pre>
              <v-empty-state
                v-else
                icon="mdi-layers-off-outline"
                title="无堆栈信息"
                text="该错误类型未携带调用栈。"
              />
            </div>
          </v-tabs-window-item>

          <!-- JSON -->
          <v-tabs-window-item value="json">
            <div class="pa-4">
              <pre
                tabindex="0"
                class="code-block text-body-small pa-3 rounded-lg bg-surface-variant"
                >{{ jsonView }}</pre>
            </div>
          </v-tabs-window-item>
        </v-tabs-window>
      </v-card-text>

      <v-divider />

      <v-card-actions class="px-4">
        <v-btn
          v-if="isSupported"
          variant="text"
          size="small"
          :color="copied ? 'success' : undefined"
          :prepend-icon="copied ? 'mdi-check-circle-outline' : 'mdi-content-copy'"
          @click="copy(jsonView)"
        >
          {{ copied ? '已复制' : '复制 JSON' }}
        </v-btn>
        <v-spacer />
        <v-btn variant="tonal" size="small" @click="visible = false">关闭</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<style lang="scss" scoped>
.code-block {
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 420px;
  overflow-y: auto;
  font-family: 'Roboto Mono', 'Courier New', monospace;

  &:focus-visible {
    outline: 2px solid rgb(var(--v-theme-primary));
    outline-offset: 2px;
  }
}

/* 详情值允许多行展示，不被列表项高度截断 */
.detail-value {
  white-space: normal;
  -webkit-line-clamp: unset;
  line-clamp: unset;
}
</style>
