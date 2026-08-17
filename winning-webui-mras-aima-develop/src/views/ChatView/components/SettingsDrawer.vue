<script setup lang="ts">
import { onMounted } from 'vue';
import { useSettings } from '@/views/ChatView/composables/useSettings';
import { useChatStore } from '@/stores/chat';

defineProps<{
  open: boolean;
}>();

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void;
}>();

const chatStore = useChatStore();

const {
  activeTab,
  settings,
  loading,
  error,
  testing,
  saving,
  message,
  connectionResults,
  connectionDrafts,
  modelDrafts,
  load,
  handleTestConnection,
  handleSaveDatabase,
  handleSaveModels,
  handleSetDefaultModel,
} = useSettings();

onMounted(() => {
  void load();
});

function providerLabel(provider: string): string {
  return provider === 'ollama' ? '本地 Ollama' : 'API 模型';
}
</script>

<template>
  <v-navigation-drawer
    :model-value="open"
    temporary
    location="right"
    width="560"
    @update:model-value="emit('update:open', $event)"
  >
    <template #prepend>
      <div class="d-flex align-center justify-space-between pa-4 border-b">
        <div>
          <h2 class="text-headline-small mb-1">系统设置</h2>
          <p class="text-body-medium text-medium-emphasis mb-0">管理对话模型和数据库连接</p>
        </div>
        <v-btn icon="mdi-close" variant="text" size="small" @click="emit('update:open', false)" />
      </div>
    </template>

    <div class="d-flex flex-column overflow-hidden h-100">
      <v-tabs v-model="activeTab" class="px-4 border-b">
        <v-tab value="models">模型</v-tab>
        <v-tab value="databases">数据库</v-tab>
      </v-tabs>

      <div class="pa-4 overflow-y-auto flex-1-1-0">
        <v-alert
          v-if="settings"
          density="compact"
          type="info"
          variant="tonal"
          class="mb-4"
          :text="settings.securityNotice || '密钥和数据库密码仅保存在本机运行设置中，不会回显。'"
        />

        <div v-if="loading" class="d-flex justify-center py-8">
          <v-progress-circular indeterminate color="primary" />
        </div>
        <v-alert v-else-if="error" type="error" variant="tonal" :text="error" class="mb-4" />

        <!-- 模型 Tab -->
        <template v-else-if="activeTab === 'models' && settings">
          <p class="text-body-medium text-medium-emphasis mb-3">
            点击模型可切换当前对话使用的模型；「设为服务默认」将作为新会话的默认模型，或展开编辑模型连接信息。
          </p>
          <v-expansion-panels multiple class="mb-4">
            <v-expansion-panel v-for="model in settings.models" :key="model.id">
              <v-expansion-panel-title
                :disabled="model.available === false"
                @click="model.available !== false && chatStore.switchModel(model.id)"
              >
                <template #default>
                  <div class="d-flex align-center justify-space-between w-100">
                    <div class="d-flex align-center gap-2">
                      <v-icon
                        :icon="
                          chatStore.currentModelId === model.id
                            ? 'mdi-radiobox-marked'
                            : 'mdi-radiobox-blank'
                        "
                        :color="chatStore.currentModelId === model.id ? 'primary' : undefined"
                        size="small"
                        class="mr-4"
                      />
                      <div>
                        <div class="text-body-medium font-weight-medium">{{ model.name }}</div>
                        <div class="text-body-small text-medium-emphasis">
                          {{ providerLabel(model.provider) }} · {{ model.model || model.id }}
                        </div>
                      </div>
                    </div>
                    <div class="d-flex align-center gap-2">
                      <v-chip
                        v-if="model.thinking"
                        size="x-small"
                        color="primary"
                        variant="tonal"
                        text="思考型"
                      />
                      <v-chip
                        v-else-if="model.available === false"
                        size="x-small"
                        color="warning"
                        variant="tonal"
                        text="需配置密钥"
                      />
                    </div>
                  </div>
                </template>
              </v-expansion-panel-title>
              <v-expansion-panel-text>
                <v-row>
                  <v-col cols="12">
                    <v-text-field
                      v-model="modelDrafts[model.id].name"
                      label="显示名称"
                      density="compact"
                      variant="outlined"
                      hide-details
                    />
                  </v-col>
                  <v-col cols="6">
                    <v-select
                      v-model="modelDrafts[model.id].provider"
                      label="提供方"
                      :items="[
                        { title: 'OpenAI 兼容 API', value: 'openai-compatible' },
                        { title: '本地 Ollama', value: 'ollama' },
                      ]"
                      density="compact"
                      variant="outlined"
                      hide-details
                    />
                  </v-col>
                  <v-col cols="6">
                    <v-text-field
                      v-model="modelDrafts[model.id].model"
                      label="模型名称"
                      density="compact"
                      variant="outlined"
                      hide-details
                    />
                  </v-col>
                  <v-col cols="12">
                    <v-text-field
                      v-model="modelDrafts[model.id].baseUrl"
                      label="服务地址 URL"
                      density="compact"
                      variant="outlined"
                      hide-details
                    />
                  </v-col>
                  <v-col cols="12">
                    <v-text-field
                      v-model="modelDrafts[model.id].completionsPath"
                      label="聊天路径（可留空）"
                      density="compact"
                      variant="outlined"
                      hide-details
                      placeholder="/chat/completions"
                    />
                  </v-col>
                  <v-col cols="12">
                    <v-text-field
                      v-model="modelDrafts[model.id].apiKey"
                      label="API Key（只写；留空不改）"
                      type="password"
                      density="compact"
                      variant="outlined"
                      hide-details
                      :placeholder="
                        model.apiKeyConfigured ? '已保存，留空不修改' : '请输入 API Key'
                      "
                      autocomplete="new-password"
                    />
                  </v-col>
                  <v-col cols="6">
                    <v-switch
                      v-model="modelDrafts[model.id].thinking"
                      label="思考型模型"
                      density="compact"
                      color="primary"
                      hide-details
                    />
                  </v-col>
                  <v-col v-if="modelDrafts[model.id].provider === 'openai-compatible'" cols="6">
                    <v-switch
                      v-model="modelDrafts[model.id].enableThinking"
                      label="开启厂商思考参数"
                      density="compact"
                      color="primary"
                      hide-details
                    />
                  </v-col>
                </v-row>
                <div class="d-flex justify-space-between align-center mt-3">
                  <v-btn
                    v-if="settings.defaultModel !== model.id"
                    variant="outlined"
                    size="small"
                    :loading="saving === `default:${model.id}`"
                    :disabled="model.available === false"
                    @click="handleSetDefaultModel(model.id)"
                  >
                    设为服务默认
                  </v-btn>
                  <div v-else />
                  <v-btn
                    variant="tonal"
                    size="small"
                    color="primary"
                    :loading="saving === 'models'"
                    @click="handleSaveModels"
                  >
                    保存模型配置
                  </v-btn>
                </div>
              </v-expansion-panel-text>
            </v-expansion-panel>
          </v-expansion-panels>
          <p v-if="message" class="text-body-medium text-primary mb-3">{{ message }}</p>
        </template>

        <!-- 数据库 Tab -->
        <template v-else-if="activeTab === 'databases' && settings">
          <p class="text-body-medium text-medium-emphasis mb-3">
            测试通过后保存，重启服务后正式生效。密码不回显。
          </p>
          <v-expansion-panels v-if="settings.databases.length" multiple class="mb-4">
            <v-expansion-panel v-for="item in settings.databases" :key="item.id">
              <v-expansion-panel-title>
                <div class="d-flex align-center justify-space-between w-100">
                  <div>
                    <div class="text-body-medium font-weight-medium">{{ item.name }}</div>
                    <div class="text-body-small text-medium-emphasis">
                      {{ item.engine }} · {{ item.purpose }}
                    </div>
                  </div>
                  <div class="d-flex align-center gap-2">
                    <v-chip
                      size="x-small"
                      :color="item.configured ? 'success' : item.enabled ? 'warning' : undefined"
                      variant="tonal"
                      :text="item.configured ? '已配置' : item.enabled ? '配置不完整' : '未启用'"
                    />
                  </div>
                </div>
              </v-expansion-panel-title>
              <v-expansion-panel-text>
                <div class="text-body-medium text-medium-emphasis mb-3">
                  <div>当前地址：{{ item.endpoint }}</div>
                  <div>
                    账号 / Schema：{{ item.username || '—'
                    }}<template v-if="item.schema"> / {{ item.schema }}</template>
                  </div>
                  <div>密码：{{ item.credentialConfigured ? '已保存（不回显）' : '未配置' }}</div>
                  <div>正式链路：{{ item.formalChain ? '是' : '否，仅扩展连接' }}</div>
                </div>
                <v-row>
                  <v-col cols="12">
                    <v-switch
                      v-model="connectionDrafts[item.id].enabled"
                      label="启用此连接"
                      density="compact"
                      color="primary"
                      hide-details
                    />
                  </v-col>
                  <v-col cols="12">
                    <v-text-field
                      v-model="connectionDrafts[item.id].driverClassName"
                      label="驱动"
                      density="compact"
                      variant="outlined"
                      hide-details
                    />
                  </v-col>
                  <v-col cols="12">
                    <v-text-field
                      v-model="connectionDrafts[item.id].url"
                      label="连接地址 URL"
                      density="compact"
                      variant="outlined"
                      hide-details
                    />
                  </v-col>
                  <v-col cols="6">
                    <v-text-field
                      v-model="connectionDrafts[item.id].username"
                      label="账号"
                      density="compact"
                      variant="outlined"
                      hide-details
                      autocomplete="username"
                    />
                  </v-col>
                  <v-col cols="6">
                    <v-text-field
                      v-model="connectionDrafts[item.id].password"
                      label="密码（只写；留空不改）"
                      type="password"
                      density="compact"
                      variant="outlined"
                      hide-details
                      autocomplete="new-password"
                      :placeholder="item.credentialConfigured ? '已保存，留空不修改' : '请输入密码'"
                    />
                  </v-col>
                  <v-col cols="6">
                    <v-text-field
                      v-model="connectionDrafts[item.id].schema"
                      label="Schema"
                      density="compact"
                      variant="outlined"
                      hide-details
                    />
                  </v-col>
                  <v-col cols="3">
                    <v-text-field
                      v-model.number="connectionDrafts[item.id].maximumPoolSize"
                      label="最大连接数"
                      type="number"
                      density="compact"
                      variant="outlined"
                      hide-details
                      min="1"
                    />
                  </v-col>
                  <v-col cols="3">
                    <v-text-field
                      v-model.number="connectionDrafts[item.id].minimumIdle"
                      label="最小空闲"
                      type="number"
                      density="compact"
                      variant="outlined"
                      hide-details
                      min="0"
                    />
                  </v-col>
                </v-row>
                <div class="d-flex align-center gap-2 mt-3">
                  <v-btn
                    variant="outlined"
                    size="small"
                    :loading="testing === item.id"
                    @click="handleTestConnection(item)"
                  >
                    测试连接
                  </v-btn>
                  <v-btn
                    variant="tonal"
                    size="small"
                    color="primary"
                    :loading="saving === `database:${item.id}`"
                    @click="handleSaveDatabase(item)"
                  >
                    保存配置
                  </v-btn>
                  <span
                    v-if="connectionResults[item.id]"
                    class="text-body-small"
                    :class="
                      connectionResults[item.id].status === 'CONNECTED'
                        ? 'text-success'
                        : 'text-error'
                    "
                  >
                    {{ connectionResults[item.id].message }} ·
                    {{ connectionResults[item.id].durationMs }} ms
                  </span>
                </div>
              </v-expansion-panel-text>
            </v-expansion-panel>
          </v-expansion-panels>
          <p v-else class="text-body-medium text-medium-emphasis">暂无可用的数据库连接。</p>
          <p v-if="message" class="text-body-medium text-primary mt-3">{{ message }}</p>
        </template>
      </div>
    </div>
  </v-navigation-drawer>
</template>
