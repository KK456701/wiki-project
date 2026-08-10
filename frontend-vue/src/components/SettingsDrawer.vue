<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import {
  loadRuntimeSettings,
  saveRuntimeConnection,
  saveRuntimeModelConfiguration,
  setRuntimeDefaultModel,
  testRuntimeConnection,
  type AgentModel,
  type ConnectionTestResult,
  type RuntimeConnectionSaveInput,
  type RuntimeConnectionTestInput,
  type RuntimeDatabaseSetting,
  type RuntimeModelConfigInput,
  type RuntimeModelSetting,
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
  settingsUpdated: []
}>()

const activeTab = ref<'models' | 'databases'>('models')
const settings = ref<RuntimeSettings | null>(null)
const loading = ref(true)
const error = ref('')
const testing = ref('')
const saving = ref('')
const message = ref('')
const connectionResults = ref<Record<string, ConnectionTestResult>>({})
const connectionDrafts = ref<Record<string, RuntimeConnectionSaveInput>>({})
const modelDrafts = ref<Record<string, RuntimeModelConfigInput>>({})

const modelItems = computed<RuntimeModelSetting[]>(() => settings.value?.models || props.models.map((model) => ({
  ...model,
  baseUrl: '',
  completionsPath: '',
  enableThinking: null,
  apiKeyConfigured: false,
})))

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    settings.value = await loadRuntimeSettings(props.token)
    modelDrafts.value = Object.fromEntries(settings.value.models.map((item) => [item.id, modelDraft(item)]))
    connectionDrafts.value = Object.fromEntries(settings.value.databases.map((item) => [item.id, connectionDraft(item)]))
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '运行配置读取失败。'
  } finally {
    loading.value = false
  }
}

function modelDraft(item: RuntimeModelSetting): RuntimeModelConfigInput {
  return {
    id: item.id,
    name: item.name,
    provider: item.provider as RuntimeModelConfigInput['provider'],
    model: item.model || '',
    baseUrl: item.baseUrl || '',
    completionsPath: item.completionsPath || '',
    apiKey: '',
    thinking: Boolean(item.thinking),
    enableThinking: item.enableThinking,
  }
}

function connectionDraft(item: RuntimeDatabaseSetting): RuntimeConnectionSaveInput {
  return {
    enabled: item.enabled,
    driverClassName: item.engine === 'Oracle' ? 'oracle.jdbc.OracleDriver' : 'com.microsoft.sqlserver.jdbc.SQLServerDriver',
    url: item.endpoint === '未配置' ? '' : item.endpoint,
    username: item.username,
    password: '',
    schema: item.schema,
    maximumPoolSize: Number(item.pool.maximumPoolSize || 2),
    minimumIdle: Number(item.pool.minimumIdle || 0),
    connectionTimeoutMs: Number(item.pool.connectionTimeoutMs || 30_000),
    validationQuery: item.engine === 'Oracle' ? 'select 1 from dual' : 'SELECT 1',
  }
}

function defaultDriver(item: RuntimeDatabaseSetting) {
  return item.engine === 'Oracle' ? 'oracle.jdbc.OracleDriver' : 'com.microsoft.sqlserver.jdbc.SQLServerDriver'
}

async function testConnection(item: RuntimeDatabaseSetting) {
  testing.value = item.id
  try {
    const draft = connectionDrafts.value[item.id]
    const changed = Boolean(draft) && (draft.driverClassName !== defaultDriver(item)
      || draft.url !== (item.endpoint === '未配置' ? '' : item.endpoint)
      || draft.username !== item.username || draft.schema !== item.schema || Boolean(draft.password))
    const testInput: RuntimeConnectionTestInput | undefined = changed ? draft : undefined
    connectionResults.value[item.id] = await testRuntimeConnection(props.token, item.id, testInput)
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

async function saveDatabase(item: RuntimeDatabaseSetting) {
  saving.value = `database:${item.id}`
  message.value = ''
  try {
    const result = await saveRuntimeConnection(props.token, item.id, connectionDrafts.value[item.id])
    message.value = result.message
    await load()
    emit('settingsUpdated')
  } catch (reason) {
    message.value = reason instanceof Error ? reason.message : '数据库配置保存失败。'
  } finally {
    saving.value = ''
  }
}

async function saveModels() {
  if (!settings.value) return
  saving.value = 'models'
  message.value = ''
  try {
    const result = await saveRuntimeModelConfiguration(props.token, {
      defaultModel: settings.value.defaultModel,
      models: modelItems.value.map((item) => modelDrafts.value[item.id]),
    })
    settings.value.defaultModel = result.defaultModel
    settings.value.models = result.models
    modelDrafts.value = Object.fromEntries(result.models.map((item) => [item.id, modelDraft(item)]))
    emit('selectModel', result.defaultModel)
    emit('settingsUpdated')
    message.value = result.message
  } catch (reason) {
    message.value = reason instanceof Error ? reason.message : '模型配置保存失败。'
  } finally {
    saving.value = ''
  }
}

async function setDefaultModel(modelId: string) {
  saving.value = `default:${modelId}`
  message.value = ''
  try {
    const result = await setRuntimeDefaultModel(props.token, modelId)
    if (settings.value) settings.value.defaultModel = result.defaultModel
    emit('selectModel', modelId)
    emit('settingsUpdated')
    message.value = result.message
  } catch (reason) {
    message.value = reason instanceof Error ? reason.message : '默认模型切换失败。'
  } finally {
    saving.value = ''
  }
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
          <p>模型配置立即生效；数据库保存后受控重启服务才切换正式连接。</p>
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
          {{ settings?.securityNotice || '密钥和数据库密码仅保存在本机运行设置中，不会回显。' }}
        </p>

        <p v-if="loading" class="settings-state">正在读取实际运行配置…</p>
        <p v-else-if="error" class="settings-state is-error">{{ error }}</p>

        <section v-else-if="activeTab === 'models'" class="settings-section">
          <header><div><span>当前浏览器</span><h3>对话模型</h3></div><small>新消息、新排查任务使用当前选择</small></header>
          <p class="settings-explain">在对话框选择的模型会写入新建排查任务；正在运行的自主排查不会中途换模型。</p>
          <div class="settings-model-list">
            <article v-for="model in modelItems" :key="model.id" class="settings-model-card" :class="{ selected: selectedModel === model.id }">
              <button type="button" :disabled="model.available === false" @click="emit('selectModel', model.id)">
                <span class="settings-radio" aria-hidden="true"></span>
                <span><strong>{{ model.name }}</strong><small>{{ providerLabel(model.provider) }} · {{ model.model || model.id }}</small></span>
                <em v-if="model.thinking">思考型</em><em v-else-if="model.available === false" class="is-off">需配置密钥</em>
              </button>
              <details class="settings-model-editor">
                <summary>编辑此模型</summary>
                <label>显示名称<input v-model="modelDrafts[model.id].name" autocomplete="off"></label>
                <label>提供方<select v-model="modelDrafts[model.id].provider"><option value="openai-compatible">OpenAI 兼容 API</option><option value="ollama">本地 Ollama</option></select></label>
                <label>模型名称<input v-model="modelDrafts[model.id].model" autocomplete="off"></label>
                <label>服务地址 URL<input v-model="modelDrafts[model.id].baseUrl" autocomplete="off"></label>
                <label>聊天路径（可留空）<input v-model="modelDrafts[model.id].completionsPath" autocomplete="off" placeholder="/chat/completions"></label>
                <label>API Key（只写；留空不改）<input v-model="modelDrafts[model.id].apiKey" type="password" autocomplete="new-password" :placeholder="model.apiKeyConfigured ? '已保存，留空不修改' : '请输入 API Key'"></label>
                <label class="settings-check"><input v-model="modelDrafts[model.id].thinking" type="checkbox"> 思考型模型</label>
                <label v-if="modelDrafts[model.id].provider === 'openai-compatible'" class="settings-check"><input v-model="modelDrafts[model.id].enableThinking" type="checkbox"> 请求时开启厂商思考参数</label>
              </details>
              <button v-if="settings?.defaultModel !== model.id" type="button" class="settings-set-default" :disabled="saving === `default:${model.id}` || model.available === false" @click="setDefaultModel(model.id)">{{ saving === `default:${model.id}` ? '正在设置…' : '设为服务默认' }}</button>
            </article>
          </div>
          <div class="settings-model-actions"><button type="button" :disabled="saving === 'models'" @click="saveModels">{{ saving === 'models' ? '正在保存…' : '保存模型配置' }}</button><span v-if="message">{{ message }}</span></div>
        </section>

        <section v-else-if="activeTab === 'databases'" class="settings-section">
          <header><div><span>服务器运行时</span><h3>数据库连接</h3></div><small>密码不回显</small></header>
          <p class="settings-explain">先填写并测试连接，再保存。保存不会打断当前抽取；重启服务后正式计算使用新连接。</p>
          <article v-for="item in settings?.databases || []" :key="item.id" class="settings-db-card">
            <header><div><strong>{{ item.name }}</strong><span>{{ item.engine }} · {{ item.purpose }}</span></div><em :data-state="item.configured ? 'ok' : item.enabled ? 'warn' : 'off'">{{ item.configured ? '已配置' : item.enabled ? '配置不完整' : '未启用' }}</em></header>
            <dl><div><dt>当前地址</dt><dd>{{ item.endpoint }}</dd></div><div><dt>账号 / Schema</dt><dd>{{ item.username || '—' }}<template v-if="item.schema"> / {{ item.schema }}</template></dd></div><div><dt>密码</dt><dd>{{ item.credentialConfigured ? '已保存（不回显）' : '未配置' }}</dd></div><div><dt>正式链路</dt><dd>{{ item.formalChain ? '是' : '否，仅扩展连接' }}</dd></div></dl>
            <details class="settings-db-editor" open>
              <summary>编辑连接配置</summary>
              <label class="settings-check"><input v-model="connectionDrafts[item.id].enabled" type="checkbox"> 启用此连接</label>
              <label>驱动<input v-model="connectionDrafts[item.id].driverClassName" autocomplete="off"></label>
              <label>连接地址 URL<input v-model="connectionDrafts[item.id].url" autocomplete="off"></label>
              <label>账号<input v-model="connectionDrafts[item.id].username" autocomplete="username"></label>
              <label>密码（只写；留空不改）<input v-model="connectionDrafts[item.id].password" type="password" autocomplete="new-password" :placeholder="item.credentialConfigured ? '已保存，留空不修改' : '请输入密码'"></label>
              <label>Schema<input v-model="connectionDrafts[item.id].schema" autocomplete="off"></label>
              <label>最大连接数<input v-model.number="connectionDrafts[item.id].maximumPoolSize" type="number" min="1"></label>
              <label>最小空闲连接<input v-model.number="connectionDrafts[item.id].minimumIdle" type="number" min="0"></label>
            </details>
            <div class="settings-db-actions"><button type="button" :disabled="testing === item.id" @click="testConnection(item)">{{ testing === item.id ? '正在测试…' : '测试连接' }}</button><button type="button" :disabled="saving === `database:${item.id}`" @click="saveDatabase(item)">{{ saving === `database:${item.id}` ? '正在保存…' : '保存配置' }}</button><span v-if="connectionResults[item.id]" :data-state="connectionResults[item.id].status.toLowerCase()">{{ connectionResults[item.id].message }} · {{ connectionResults[item.id].durationMs }} ms</span></div>
          </article>
          <p v-if="message" class="settings-message">{{ message }}</p>
        </section>
      </main>
    </aside>
  </div>
</template>
