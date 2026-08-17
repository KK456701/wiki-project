<script setup lang="ts">
import { onMounted, ref, watch, computed } from 'vue';
import { storeToRefs } from 'pinia';
import { useTheme } from 'vuetify';
import { useChatStore } from '@/stores/chat';
import { setHighlightTheme } from '@/utils/markdown';
import { APP_TITLE } from '@/config/app';
import { MESSAGE_ROLE, MESSAGE_STATUS } from '@/types/chat';
import { getRulesList, type RuleItem } from '@/services/chat';
import { useChatView } from './composables/useChatView';
import { useClarification } from './composables/useClarification';
import { useAutoScroll } from './composables/useAutoScroll';
import { useDrawer } from './composables/useDrawer';
import ChatMessage from './components/ChatMessage.vue';
import ChatHeader from './components/ChatHeader.vue';
import ChatInput from './components/ChatInput.vue';
import SessionSidebar from './components/SessionSidebar.vue';
import ClarificationDialog from './components/ClarificationDialog.vue';
import DeleteConfirmDialog from './components/DeleteConfirmDialog.vue';
import AnnotationDrawer from './components/AnnotationDrawer.vue';
import SettingsDrawer from './components/SettingsDrawer.vue';
import RecommendedQuestions from './components/RecommendedQuestions.vue';
import QuickActions from './components/QuickActions.vue';
import { RECOMMENDED_QUESTIONS } from './constants';
import { useWorkspaceRedirect } from './composables/useWorkspaceRedirect';

const chatStore = useChatStore();
const { currentSession } = storeToRefs(chatStore);
const {
  showError,
  errorMessage,
  isInitialLoading,
  deleteConfirmVisible,
  requestDelete,
  confirmDelete,
  selectSession,
  handleCreateSession,
} = useChatView();
const { showClarification, currentClarification, confirmClarification, dismissClarification } =
  useClarification();
const messagesContainer = ref<HTMLElement | null>(null);
useAutoScroll(messagesContainer);
const { drawerOpen, lgAndUp, toggleDrawer } = useDrawer();
const annotationDrawerOpen = ref(false);
const settingsDrawerOpen = ref(false);
const theme = useTheme();
useWorkspaceRedirect();

/** 指标规则列表（页面级初始化时加载一次，通过 props 传递给 QuickActions） */
const rules = ref<RuleItem[]>([]);

/**
 * 是否在助手回复完成后显示推荐问题
 * 条件：最后一条消息是 assistant 且状态为 completed，且当前不在流式输出中
 */
const showRecommendedQuestions = computed(() => {
  const messages = chatStore.currentMessages;
  if (messages.length === 0) return false;
  if (chatStore.isStreaming) return false;

  const lastMessage = messages[messages.length - 1];
  return (
    lastMessage.role === MESSAGE_ROLE.ASSISTANT && lastMessage.status === MESSAGE_STATUS.COMPLETED
  );
});

// 初始化 highlight.js 主题（应用启动时注入当前主题对应的 CSS）
onMounted(() => {
  setHighlightTheme(theme.global.current.value.dark);
});

// 页面级加载一次指标规则列表（数据变化不频繁，无需每次实时查询）
onMounted(async () => {
  try {
    rules.value = await getRulesList();
  } catch {
    // 静默处理，下拉无选项
  }
});

// 主题切换时同步更新代码高亮主题
watch(
  () => theme.global.current.value.dark,
  (isDark) => setHighlightTheme(isDark),
);

function toggleTheme() {
  theme.change(theme.global.current.value.dark ? 'light' : 'dark');
}

function handleSend(content: string) {
  chatStore.sendMessage(content);
}

function handleRecommendedQuestion(content: string) {
  if (!chatStore.currentModelId) {
    errorMessage.value = '请先选择一个模型';
    showError.value = true;
    return;
  }
  if (chatStore.isStreaming) {
    errorMessage.value = '当前正在回复中，请等待回复完成后再发送';
    showError.value = true;
    return;
  }
  chatStore.sendMessage(content);
}

function handleStop() {
  chatStore.stopStreaming();
}

function onTroubleshootError(message: string) {
  errorMessage.value = message;
  showError.value = true;
}

function handleModelChange(modelId: string) {
  chatStore.switchModel(modelId);
}
</script>

<template>
  <ChatHeader
    :title="currentSession?.title || APP_TITLE"
    :dark="theme.global.current.value.dark"
    @toggle-drawer="toggleDrawer"
    @toggle-theme="toggleTheme"
    @toggle-annotations="annotationDrawerOpen = !annotationDrawerOpen"
    @open-settings="settingsDrawerOpen = true"
    @create-session="handleCreateSession"
  />

  <!-- 侧边栏 -->
  <v-navigation-drawer
    v-model="drawerOpen"
    :temporary="!lgAndUp"
    :persistent="lgAndUp"
    width="280"
    app
  >
    <SessionSidebar
      :sessions="chatStore.sessions"
      :current-session-id="chatStore.currentSessionId"
      :loading="chatStore.loadingSessions"
      @select="selectSession"
      @create="handleCreateSession"
      @delete="requestDelete"
    />
  </v-navigation-drawer>

  <!-- 主内容区 -->
  <v-main>
    <div class="d-flex flex-column overflow-hidden chat-main">
      <!-- 消息列表 -->
      <div
        ref="messagesContainer"
        class="d-flex flex-column overflow-y-auto bg-surface messages-container"
      >
        <div class="mx-auto w-100 messages-wrapper">
          <!-- 初始加载中：显示骨架屏 -->
          <div v-if="isInitialLoading" class="px-4 py-6">
            <div v-for="n in 3" :key="n">
              <v-skeleton-loader type="list-item-avatar-two-line" class="mb-4" />
            </div>
          </div>

          <!-- 非加载态：空状态或消息列表 -->
          <template v-else>
            <div
              v-if="!chatStore.loadingSessionMessages && chatStore.currentMessages.length === 0"
              class="d-flex flex-column align-center justify-center text-center h-100 empty-state"
            >
              <v-icon icon="mdi-robot-happy" size="64" color="primary" class="robot-float mb-4" />
              <h3 class="text-headline-small mb-2">开始新的对话</h3>
              <p class="text-body-medium text-medium-emphasis">
                输入您的问题，{{ APP_TITLE }}将为您提供帮助
              </p>
              <QuickActions
                class="mt-4"
                :rules="rules"
                @select="handleRecommendedQuestion"
                @error="onTroubleshootError"
              />
              <RecommendedQuestions
                :questions="RECOMMENDED_QUESTIONS"
                class="mt-6"
                @select="handleRecommendedQuestion"
              />
            </div>

            <ChatMessage
              v-for="message in chatStore.currentMessages"
              :key="message.id"
              :message="message"
            />

            <!-- 助手回复完成后显示推荐问题 -->
            <div v-if="showRecommendedQuestions" class="d-flex justify-center mt-4 mb-6">
              <RecommendedQuestions
                :questions="RECOMMENDED_QUESTIONS"
                @select="handleRecommendedQuestion"
              />
            </div>
          </template>
        </div>
      </div>

      <!-- 输入区域 -->
      <ChatInput
        :disabled="chatStore.isStreaming"
        :streaming="chatStore.isStreaming"
        :models="chatStore.models"
        :current-model-id="chatStore.currentModelId"
        @send="handleSend"
        @stop="handleStop"
        @update:model-id="handleModelChange"
      />
    </div>

    <!-- 澄清对话框 -->
    <ClarificationDialog
      v-model="showClarification"
      :clarification="currentClarification"
      @confirm="confirmClarification"
      @update:model-value="dismissClarification"
    />

    <!-- 删除确认对话框 -->
    <DeleteConfirmDialog v-model="deleteConfirmVisible" @confirm="confirmDelete" />

    <!-- 错误提示 -->
    <v-snackbar v-model="showError" color="error" timeout="5000" location="top">
      {{ errorMessage }}
      <template #actions>
        <v-btn variant="text" @click="showError = false">关闭</v-btn>
      </template>
    </v-snackbar>
  </v-main>

  <!-- 标注说明抽屉 -->
  <AnnotationDrawer v-model:open="annotationDrawerOpen" />

  <!-- 系统设置抽屉 -->
  <SettingsDrawer v-model:open="settingsDrawerOpen" />
</template>

<style lang="scss" scoped>
@use './styles/index.scss';
</style>
