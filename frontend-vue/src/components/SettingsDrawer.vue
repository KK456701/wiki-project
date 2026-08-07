<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import {
  loadRuntimeSettings,
  setRuntimeDefaultModel,
  testRuntimeConnection,
  type AgentModel,
  type ConnectionTestResult,
  type RuntimeConnectionTestInput,
  type RuntimeDatabaseSetting,
  type RuntimeSettings,
} from '../api/agent'

const props = defineProps<{
  token: string
  selectedModel: string
  models: AgentModel[]
}>()

const emit = defineEmits<{
  close: []
  selectModel: [modelId: string]
}>()

const activeTab = ref<'models' | 'databases'>('models')
const settings = ref<RuntimeSettings | null>(null)
const loading = ref(true)
const error = ref('')
const testing = ref('')
const connectionResults = ref<Record<string, ConnectionTestResult>>({})
const connectionDrafts = ref<Record<string, RuntimeConnectionTestInput>>({})
const savingDefault = ref('')
const modelMessage = ref('')

const modelItems = computed(() => settings.value?.models?.length ? settings.value.models : props.models)

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    settings.value = await loadRuntimeSettings(props.token)
    connectionDrafts.value = Object.fromEntries(settings.value.databases.map((item) => [item.id, {
      driverClassName: item.engine === 'Oracle' ? 'oracle.jdbc.OracleDriver' : 'com.microsoft.sqlserver.jdbc.SQLServerDriver',
      url: item.endpoint === '未配置' ? '' : item.endpoint,
      username: item.username,
      password: '',
      schema: item.schema,
    }]))
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '运行配置读取失败。'
  } finally {
    loading.value = false
  }
}

async function testConnection(item: RuntimeDatabaseSetting) {
  testing.value = item.id
  try {
    const draft = connectionDrafts.value[item.id]
    const changed = draft && (draft.driverClassName !== defaultDriver(item)
      || draft.url !== (item.endpoint === '未配置' ? '' : item.endpoint)
      || draft.username !== item.username || draft.schema !== item.schema || Boolean(draft.password))
    connectionResults.value[item.id] = await testRuntimeConnection(props.token, item.id, changed ? draft : undefined)
  } catch (reason) {
    connectionResults.value[item.id] = {
      connectionId: item.id,
      status: 'FAILED',
      message: reason instanceof Error ? reason.message : '连接测试失败。',
      durationMs: 0,
    }
  } finally {
    testing.value = ''
  }
}

async function setDefaultModel(modelId: string) {
  savingDefault.value = modelId
  modelMessage.value = ''
  try {
    const result = await setRuntimeDefaultModel(props.token, modelId)
    if (settings.value) settings.value.defaultModel = result.defaultModel
    emit('selectModel', modelId)
    modelMessage.value = result.message
  } catch (reason) {
    modelMessage.value = reason instanceof Error ? reason.message : '默认模型切换失败。'
  } finally {
    savingDefault.value = ''
  }
}

function defaultDriver(item: RuntimeDatabaseSetting) {
  return item.engine === 'Oracle' ? 'oracle.jdbc.OracleDriver' : 'com.microsoft.sqlserver.jdbc.SQLServerDriver'
}

function providerLabel(provider: string) {
  return provider === 'ollama' ? '本地 Ollama' : 'API 模型'
}
</script>

<template>
  <div class="settings-backdrop" role="presentation" @click.self="emit('close')">
    <aside class="settings-drawer" role="dialog" aria-modal="true" aria-labelledby="settings-title">
      <header class="settings-head">
        <div>
          <span class="settings-kicker">运行控制台</span>
          <h2 id="settings-title">系统设置</h2>
          <p>切换模型，并核对或测试数据库连接。</p>
        </div>
        <button type="button" class="drawer-close" aria-label="关闭设置" @click="emit('close')">×</button>
      </header>

      <nav class="settings-tabs" aria-label="设置分类">
        <button type="button" :class="{ active: activeTab === 'models' }" @click="activeTab = 'models'">模型</button>
        <button type="button" :class="{ active: activeTab === 'databases' }" @click="activeTab = 'databases'">数据库</button>
      </nav>

      <main class="settings-body">
        <p class="settings-security">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3 5 6v5c0 4.6 2.8 8 7 10 4.2-2 7-5.4 7-10V6l-7-3Z"/><path d="m9 12 2 2 4-4"/></svg>
          {{ settings?.securityNotice || '密钥与密码只保存在服务器环境变量中，不会显示在浏览器。' }}
        </p>

        <p v-if="loading" class="settings-state">正在读取实际运行配置…</p>
        <p v-else-if="error" class="settings-state is-error">{{ error }}</p>

        <section v-else-if="activeTab === 'models'" class="settings-section">
          <header><div><span>本浏览器</span><h3>选择对话模型</h3></div><small>立即生效</small></header>
          <p class="settings-explain">用于新发送的问答和新建异常排查任务；正在运行的任务不会中途换模型。</p>
          <div class="settings-model-list">
            <button
              v-for="model in modelItems"
              :key="model.id"
              type="button"
              :class="{ selected: selectedModel === model.id }"
              :disabled="model.available === false"
              @click="emit('selectModel', model.id)"
            >
              <span class="settings-radio" aria-hidden="true"></span>
              <span><strong>{{ model.name }}</strong><small>{{ providerLabel(model.provider) }} · {{ model.model || model.id }}</small></span>
              <em v-if="model.thinking">思考型</em>
              <em v-else-if="model.available === false" class="is-off">缺少密钥</em>
            </button>
          </div>
          <div class="settings-model-actions">
            <button type="button" :disabled="savingDefault === selectedModel" @click="setDefaultModel(selectedModel)">
              {{ savingDefault === selectedModel ? '正在切换…' : '设为服务默认模型' }}
            </button>
            <span v-if="modelMessage">{{ modelMessage }}</span>
          </div>
          <div class="settings-config-note"><strong>API Key 配置</strong><code>DEEPSEEK_API_KEY</code><code>DASHSCOPE_API_KEY</code><p>修改服务器环境变量后重启 Java 服务生效，页面不会接收或保存 Key。</p></div>
        </section>

        <section v-else-if="activeTab === 'databases'" class="settings-section">
          <header><div><span>服务器运行时</span><h3>数据库连接</h3></div><small>密码不回显</small></header>
          <p class="settings-explain">可编辑连接参数后立即测试。密码只用于本次测试，不保存或回显；正式计算的业务库/真实库仍由服务器环境变量和重启后的连接池控制。</p>
          <article v-for="item in settings?.databases || []" :key="item.id" class="settings-db-card">
            <header>
              <div><strong>{{ item.name }}</strong><span>{{ item.engine }} · {{ item.purpose }}</span></div>
              <em :data-state="item.configured ? 'ok' : item.enabled ? 'warn' : 'off'">{{ item.configured ? '已配置' : item.enabled ? '配置不完整' : '未启用' }}</em>
            </header>
            <dl>
              <div><dt>连接地址</dt><dd>{{ item.endpoint }}</dd></div>
              <div><dt>账号 / Schema</dt><dd>{{ item.username || '—' }}<template v-if="item.schema"> / {{ item.schema }}</template></dd></div>
              <div><dt>密码</dt><dd>{{ item.credentialConfigured ? '已通过环境变量配置' : '未配置' }}</dd></div>
              <div><dt>连接池</dt><dd>{{ Object.entries(item.pool).map(([key, value]) => `${key}=${value}`).join(' · ') }}</dd></div>
            </dl>
            <div class="settings-db-actions">
              <button type="button" :disabled="testing === item.id || !item.enabled" @click="testConnection(item)">{{ testing === item.id ? '正在测试…' : '测试连接' }}</button>
              <span v-if="connectionResults[item.id]" :data-state="connectionResults[item.id].status.toLowerCase()">{{ connectionResults[item.id].message }} · {{ connectionResults[item.id].durationMs }} ms</span>
            </div>
            <details class="settings-db-editor">
              <summary>修改本次测试连接</summary>
              <p>如修改地址、账号、驱动或 Schema，请重新填写密码后测试。该填写内容不会写入知识库或显示在页面。</p>
              <label>驱动<input v-model="connectionDrafts[item.id].driverClassName" autocomplete="off"></label>
              <label>连接地址<input v-model="connectionDrafts[item.id].url" autocomplete="off"></label>
              <label>账号<input v-model="connectionDrafts[item.id].username" autocomplete="username"></label>
              <label>密码<input v-model="connectionDrafts[item.id].password" type="password" autocomplete="new-password" placeholder="修改连接后请重新填写"></label>
              <label>Schema<input v-model="connectionDrafts[item.id].schema" autocomplete="off"></label>
            </details>
            <details><summary>环境变量</summary><code v-for="name in item.environmentVariables" :key="name">{{ name }}</code></details>
          </article>
        </section>

      </main>
    </aside>
  </div>
</template>
