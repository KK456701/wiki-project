<script setup lang="ts">
import { ref, computed } from 'vue';
import type { ModelInfo } from '@/types/chat';

const emit = defineEmits<{
  send: [content: string];
  stop: [];
  'update:modelId': [modelId: string];
}>();

const props = defineProps<{
  disabled?: boolean;
  streaming?: boolean;
  models?: ModelInfo[];
  currentModelId?: string | null;
}>();

const inputText = ref('');

// 是否有可用模型
const hasModels = computed(() => (props.models?.length ?? 0) > 0);

// 是否已选择模型
const hasSelectedModel = computed(() => !!props.currentModelId);

// 当前选中的模型名称
const currentModelName = computed(() => {
  if (!props.currentModelId || !props.models) return '';
  const model = props.models.find((m) => m.id === props.currentModelId);
  return model?.name || '';
});

// 输入框是否禁用（未选择模型或外部禁用）
const isInputDisabled = computed(() => props.disabled || !hasSelectedModel.value);

function handleSend() {
  const text = inputText.value.trim();
  if (!text || props.disabled) return;
  emit('send', text);
  inputText.value = '';
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function handleKeydown(e: any) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault();
    if (!isInputDisabled.value) {
      handleSend();
    }
  }
}
</script>

<template>
  <div class="chat-input-wrapper bg-surface">
    <v-card rounded="xl" elevation="2" class="chat-input-card">
      <!--
        大屏（sm+）：输入框 + 模型选择 + 发送按钮 同排显示
        小屏（xs）：第 1 行输入框，第 2 行模型选择 + 发送按钮
      -->
      <div class="d-flex flex-column flex-sm-row align-sm-end pa-3 ga-2">
        <!-- 文本输入 -->
        <v-textarea
          v-model="inputText"
          :placeholder="
            hasSelectedModel ? '输入消息... (Enter 发送, Shift+Enter 换行)' : '请先选择模型'
          "
          auto-grow
          rows="1"
          max-rows="6"
          variant="plain"
          hide-details
          density="comfortable"
          class="chat-textarea flex-fill"
          :disabled="isInputDisabled"
          @keydown="handleKeydown"
        />

        <!-- 操作按钮（小屏时靠右对齐） -->
        <div class="d-flex align-center justify-end ga-1 flex-shrink-0">
          <!-- 模型选择器 -->
          <v-menu>
            <template #activator="{ props: menuProps }">
              <v-btn
                v-bind="menuProps"
                size="default"
                variant="tonal"
                :color="hasSelectedModel ? 'primary' : 'red'"
                :disabled="!hasModels || disabled || streaming"
                class="model-selector-btn"
              >
                <v-icon start icon="mdi-robot" />
                <span class="model-name">
                  {{ currentModelName || '选择模型' }}
                </span>
                <v-icon end icon="mdi-menu-down" size="small" />
              </v-btn>
            </template>
            <v-list density="compact">
              <v-list-item
                v-for="model in models"
                :key="model.id"
                :active="model.id === currentModelId"
                @click="emit('update:modelId', model.id)"
              >
                <v-list-item-title>{{ model.name }}</v-list-item-title>
                <v-list-item-subtitle>{{ model.provider }}</v-list-item-subtitle>
              </v-list-item>
            </v-list>
          </v-menu>

          <!-- 停止按钮 -->
          <v-btn
            v-if="streaming"
            icon="mdi-stop"
            color="error"
            size="small"
            variant="tonal"
            @click="emit('stop')"
          />

          <!-- 发送按钮 -->
          <v-btn
            v-else
            icon="mdi-send"
            color="primary"
            size="small"
            :disabled="!inputText.trim() || isInputDisabled"
            @click="handleSend"
          />
        </div>
      </div>
    </v-card>
  </div>
</template>

<style lang="scss" scoped>
.chat-input-wrapper {
  padding: 12px 20px 20px;
  // background: linear-gradient(to top, rgb(var(--v-theme-surface)) 60%, transparent);
}

.chat-input-card {
  max-width: 900px;
  margin: 0 auto;
}

.chat-textarea :deep(.v-field) {
  padding: 0;
}

.chat-textarea :deep(textarea) {
  font-size: 14px;
  line-height: 1.5;
}

.model-selector-btn {
  min-width: 120px !important;
  text-transform: none !important;
  font-weight: 500 !important;
  letter-spacing: 0 !important;
}

.model-name {
  max-width: 100px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
