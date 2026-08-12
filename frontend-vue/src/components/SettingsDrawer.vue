<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import {
  loadApplicationLogs,
  loadRuntimeSettings,
  saveRuntimeConnection,
  saveRuntimeModelConfiguration,
  setRuntimeDefaultModel,
  testRuntimeConnection,
  testRuntimeModel,
  type AgentModel,
  type ApplicationLogSnapshot,
  type ConnectionTestResult,
  type RuntimeConnectionSaveInput,
  type RuntimeConnectionTestInput,
  type RuntimeDatabaseSetting,
  type RuntimeModelConfigInput,
  type RuntimeModelSetting,
  type ModelTestResult,
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

const activeTab = ref<'models' | 'databases' | 'logs'>('models')
const settings = ref<RuntimeSettings | null>(null)
const loading = ref(true)
const error = ref('')
const testing = ref('')
const saving = ref('')
const message = ref('')
const connectionResults = ref<Record<string, ConnectionTestResult>>({})
const connectionDrafts = ref<Record<string, RuntimeConnectionSaveInput>>({})
const modelDrafts = ref<Record<string, RuntimeModelConfigInput>>({})
const logSnapshot = ref<ApplicationLogSnapshot | null>(null)
const logLevel = ref<'ERROR' | 'ALL'>('ERROR')
const logSearch = ref('')
const logLoading = ref(false)
const logMessage = ref('')
const modelTesting = ref('')
const modelResults = ref<Record<string, ModelTestResult>>({})

const modelItems = computed<RuntimeModelSetting[]>(() => settings.value?.models || props.models.map((model) => ({
  ...model,
  baseUrl: '',
  completionsPath: '',
  enableThinking: null,
  contextWindowTokens: model.provider === 'ollama' ? 16384 : null,
  apiKeyConfigured: false,
})))

const modelGroups = computed(() => [
  {
    key: 'local',
    title: '本地 Ollama 模型',
    description: '运行在本院或内网设备，不经过云端 API',
    items: modelItems.value.filter((item) => item.provider === 'ollama'),
  },
  {
    key: 'cloud',
    title: '云端 API 模型',
    description: '通过已配置的服务地址和 API Key 调用',
    items: modelItems.value.filter((item) => item.provider !== 'ollama'),
  },
].filter((group) => group.items.length > 0))

const databaseGroups = computed(() => {
  const items = settings.value?.databases || []
  return [
    {
      key: 'business',
      title: '业务库',
      description: '当前唯一启用的医院 Oracle 指标源数据连接',
      items: items.filter((item) => item.role === 'BUSINESS'),
    },
    {
      key: 'real',
      title: '真实库',
      description: '指标中间表、统计 SQL 和正式结果',
      items: items.filter((item) => item.role === 'REAL'),
    },
  ].filter((group) => group.items.length > 0)
})

const visibleLogContent = computed(() => {
  const content = logSnapshot.value?.content || ''
  const keyword = logSearch.value.trim().toLowerCase()
  if (!keyword) return content
  return content.split(/\r?\n/).filter((line) => line.toLowerCase().includes(keyword)).join('\n')
})

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
    contextWindowTokens: item.provider === 'ollama' ? Number(item.contextWindowTokens || 16384) : null,
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

async function testConnection(item: RuntimeDatabaseSetting): Promise<boolean> {
  testing.value = item.id
  try {
    const draft = connectionDrafts.value[item.id]
    const changed = Boolean(draft) && (draft.driverClassName !== defaultDriver(item)
      || draft.url !== (item.endpoint === '未配置' ? '' : item.endpoint)
      || draft.username !== item.username || draft.schema !== item.schema || Boolean(draft.password))
    const testInput: RuntimeConnectionTestInput | undefined = changed ? draft : undefined
    connectionResults.value[item.id] = await testRuntimeConnection(props.token, item.id, testInput)
    return connectionResults.value[item.id].status === 'CONNECTED'
  } catch (reason) {
    connectionResults.value[item.id] = {
      connectionId: item.id,
      status: 'FAILED',
      message: reason instanceof Error ? reason.message : '连接测试失败。',
      durationMs: 0,
    }
    return false
  } finally {
    testing.value = ''
  }
}

async function saveDatabase(item: RuntimeDatabaseSetting): Promise<boolean> {
  saving.value = `database:${item.id}`
  message.value = ''
  try {
    const result = await saveRuntimeConnection(props.token, item.id, connectionDrafts.value[item.id])
    message.value = result.message
    await load()
    emit('settingsUpdated')
    return true
  } catch (reason) {
    message.value = reason instanceof Error ? reason.message : '数据库配置保存失败。'
    return false
  } finally {
    saving.value = ''
  }
}

async function testAndApplyDatabase(item: RuntimeDatabaseSetting) {
  message.value = ''
  if (!await testConnection(item)) return
  await saveDatabase(item)
}

async function saveModels(preferredModelId = '') {
  if (!settings.value) return
  saving.value = preferredModelId ? `model:${preferredModelId}` : 'models'
  message.value = ''
  try {
    const result = await saveRuntimeModelConfiguration(props.token, {
      defaultModel: settings.value.defaultModel,
      models: modelItems.value.map((item) => modelDrafts.value[item.id]),
    })
    settings.value.defaultModel = result.defaultModel
    settings.value.models = result.models
    modelDrafts.value = Object.fromEntries(result.models.map((item) => [item.id, modelDraft(item)]))
    const preferred = result.models.find((item) => item.id === preferredModelId)
    emit('selectModel', preferred?.available === false || !preferredModelId
      ? result.defaultModel
      : preferredModelId)
    emit('settingsUpdated')
    message.value = preferredModelId && preferred?.available !== false
      ? '模型配置已保存并启用，现在可以在对话框中选择使用。'
      : result.message
  } catch (reason) {
    message.value = reason instanceof Error ? reason.message : '模型配置保存失败。'
  } finally {
    saving.value = ''
  }
}

async function testAndUseModel(model: RuntimeModelSetting) {
  modelTesting.value = model.id
  message.value = ''
  try {
    const result = await testRuntimeModel(props.token, modelDrafts.value[model.id])
    modelResults.value[model.id] = result
    if (result.status !== 'CONNECTED') return
    await saveModels(model.id)
  } catch (reason) {
    modelResults.value[model.id] = {
      modelId: model.id,
      status: 'FAILED',
      message: reason instanceof Error ? reason.message : '模型测试失败。',
      durationMs: 0,
    }
  } finally {
    modelTesting.value = ''
  }
}

function clearModelTest(modelId: string) {
  delete modelResults.value[modelId]
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

async function loadLogs() {
  logLoading.value = true
  logMessage.value = ''
  try {
    logSnapshot.value = await loadApplicationLogs(props.token, logLevel.value, 600)
  } catch (reason) {
    logMessage.value = reason instanceof Error ? reason.message : '错误日志读取失败。'
  } finally {
    logLoading.value = false
  }
}

async function selectLogs() {
  activeTab.value = 'logs'
  await loadLogs()
}

async function copyLogs() {
  if (!visibleLogContent.value) return
  try {
    await navigator.clipboard.writeText(visibleLogContent.value)
    logMessage.value = '当前日志已复制。'
  } catch {
    logMessage.value = '浏览器未允许复制，请选中文本后手动复制。'
  }
}
</script>

<template>
  <div class="settings-backdrop" role="presentation" @click.self="emit('close')">
    <aside class="settings-drawer" role="dialog" aria-modal="true" aria-labelledby="settings-title">
      <header class="settings-head">
        <div>
          <h2 id="settings-title">系统设置</h2>
          <p>管理运行配置并查看错误原因</p>
        </div>
        <button type="button" class="drawer-close" aria-label="关闭设置" @click="emit('close')">×</button>
      </header>

      <nav class="settings-tabs" aria-label="设置分类">
        <button type="button" :class="{ active: activeTab === 'models' }" @click="activeTab = 'models'">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m12 3 7 4-7 4-7-4 7-4Z"/><path d="m5 7 7 4 7-4v9l-7 4-7-4V7Z"/></svg><span>模型</span>
        </button>
        <button type="button" :class="{ active: activeTab === 'databases' }" @click="activeTab = 'databases'">
          <svg viewBox="0 0 24 24" aria-hidden="true"><ellipse cx="12" cy="5" rx="7" ry="3"/><path d="M5 5v7c0 1.7 3.1 3 7 3s7-1.3 7-3V5"/><path d="M5 12v7c0 1.7 3.1 3 7 3s7-1.3 7-3v-7"/></svg><span>数据库</span>
        </button>
        <button type="button" :class="{ active: activeTab === 'logs' }" @click="selectLogs">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 3h9l4 4v14H6V3Z"/><path d="M15 3v5h4M9 12h6M9 16h6"/></svg><span>错误日志</span>
        </button>
      </nav>

      <main class="settings-body">
        <p class="settings-security">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3 5 6v5c0 4.6 2.8 8 7 10 4.2-2 7-5.4 7-10V6l-7-3Z"/><path d="m9 12 2 2 4-4"/></svg>
          <span><strong>安全提示</strong>{{ settings?.securityNotice || '密钥和数据库密码仅保存在本机运行设置中，不会回显。' }}<small>配置测试通过后立即生效，不会写入知识库。</small></span>
        </p>

        <p v-if="loading" class="settings-state">正在读取实际运行配置…</p>
        <p v-else-if="error" class="settings-state is-error">{{ error }}</p>

        <section v-else-if="activeTab === 'models'" class="settings-section">
          <header><div><h3>对话模型</h3><p>先选择本地或云端类型，再查看和测试具体模型。</p></div><small>测试通过后立即可用</small></header>
          <div class="settings-config-groups">
            <details v-for="group in modelGroups" :key="group.key" class="settings-config-group">
              <summary>
                <span class="settings-group-icon" :data-kind="group.key">{{ group.key === 'local' ? '本' : '云' }}</span>
                <span><strong>{{ group.title }}</strong><small>{{ group.description }}</small></span>
                <em>{{ group.items.length }} 个</em>
              </summary>
              <div class="settings-model-list">
                <article v-for="model in group.items" :key="model.id" class="settings-model-card" :class="{ selected: selectedModel === model.id }">
                  <button type="button" :disabled="model.available === false" @click="emit('selectModel', model.id)">
                    <span class="settings-radio" aria-hidden="true"></span>
                    <span><strong>{{ model.name }}</strong><small>{{ providerLabel(model.provider) }} · {{ model.model || model.id }}</small></span>
                    <em v-if="model.thinking">思考型</em><em v-else-if="model.available === false" class="is-off">待配置</em>
                  </button>
                  <details class="settings-model-editor" @input="clearModelTest(model.id)">
                    <summary>查看和编辑配置</summary>
                    <label>显示名称<input v-model="modelDrafts[model.id].name" autocomplete="off"></label>
                    <label>提供方<select v-model="modelDrafts[model.id].provider"><option value="openai-compatible">OpenAI 兼容 API</option><option value="ollama">本地 Ollama</option></select></label>
                    <label>模型名称<input v-model="modelDrafts[model.id].model" autocomplete="off"></label>
                    <label>服务地址 URL<input v-model="modelDrafts[model.id].baseUrl" autocomplete="off"></label>
                    <label>聊天路径（可留空）<input v-model="modelDrafts[model.id].completionsPath" autocomplete="off" placeholder="/chat/completions"></label>
                    <label>API Key（只写；留空不改）<input v-model="modelDrafts[model.id].apiKey" type="password" autocomplete="new-password" :placeholder="model.apiKeyConfigured ? '已保存，留空不修改' : '请输入 API Key'"></label>
                    <label class="settings-check"><input v-model="modelDrafts[model.id].thinking" type="checkbox"> 思考型模型</label>
                    <label v-if="modelDrafts[model.id].provider === 'openai-compatible'" class="settings-check"><input v-model="modelDrafts[model.id].enableThinking" type="checkbox"> 请求时开启厂商思考参数</label>
                    <label v-if="model.provider === 'ollama' || modelDrafts[model.id].provider === 'ollama'">上下文容量（tokens）<input v-model.number="modelDrafts[model.id].contextWindowTokens" type="number" min="8192" max="131072" step="4096"><small>建议先用 16384；增大后会占用更多内存或显存。</small></label>
                    <button type="button" class="settings-save-model" :disabled="modelTesting === model.id || saving === `model:${model.id}`" @click="testAndUseModel(model)">{{ modelTesting === model.id ? '正在发送测试请求…' : saving === `model:${model.id}` ? '正在启用…' : '测试并启用此模型' }}</button>
                    <span v-if="modelResults[model.id]" class="settings-inline-result" :data-state="modelResults[model.id].status.toLowerCase()">{{ modelResults[model.id].message }} · {{ modelResults[model.id].durationMs }} ms</span>
                  </details>
                  <button v-if="settings?.defaultModel !== model.id" type="button" class="settings-set-default" :disabled="saving === `default:${model.id}` || model.available === false" @click="setDefaultModel(model.id)">{{ saving === `default:${model.id}` ? '正在设置…' : '设为服务默认' }}</button>
                </article>
              </div>
            </details>
          </div>
          <p v-if="message" class="settings-message">{{ message }}</p>
        </section>

        <section v-else-if="activeTab === 'databases'" class="settings-section">
          <header><div><h3>数据库连接</h3><p>只保留当前业务库和真实库各一份配置；测试通过后直接热更新。</p></div><small>密码不回显</small></header>
          <p class="settings-config-source">部署时填写外部 application.properties，服务首次启动会自动带入这里；页面修改只保存到本机运行设置，不会改写部署文件。</p>
          <div class="settings-config-groups">
            <details v-for="group in databaseGroups" :key="group.key" class="settings-config-group settings-database-group">
              <summary>
                <span class="settings-group-icon" :data-kind="group.key">{{ group.key === 'business' ? '业' : '真' }}</span>
                <span><strong>{{ group.title }}</strong><small>{{ group.description }}</small></span>
                <em>{{ group.items.length }} 个连接</em>
              </summary>
              <article v-for="item in group.items" :key="item.id" class="settings-db-card" :class="{ active: item.active }">
                <header><div><strong>{{ item.name }}</strong><span>{{ item.engine }} · {{ item.purpose }}</span></div><em :data-state="item.configured ? 'ok' : item.enabled ? 'warn' : 'off'">{{ item.configured ? '正在使用' : item.enabled ? '配置不完整' : '未启用' }}</em></header>
                <dl><div><dt>当前地址</dt><dd>{{ item.endpoint }}</dd></div><div><dt>账号 / Schema</dt><dd>{{ item.username || '—' }}<template v-if="item.schema"> / {{ item.schema }}</template></dd></div><div><dt>密码</dt><dd>{{ item.credentialConfigured ? '已保存（不回显）' : '未配置' }}</dd></div><div><dt>运行角色</dt><dd>{{ item.role === 'BUSINESS' ? (item.active ? '正在提供业务源数据' : '候选业务库连接') : '正式中间表与统计结果' }}</dd></div></dl>
                <details class="settings-db-editor">
                  <summary>查看和编辑连接配置</summary>
                  <label class="settings-check"><input v-model="connectionDrafts[item.id].enabled" type="checkbox"> 启用此连接</label>
                  <label>驱动<input v-model="connectionDrafts[item.id].driverClassName" autocomplete="off"></label>
                  <label>连接地址 URL<input v-model="connectionDrafts[item.id].url" autocomplete="off"></label>
                  <label>账号<input v-model="connectionDrafts[item.id].username" autocomplete="username"></label>
                  <label>密码（只写；留空不改）<input v-model="connectionDrafts[item.id].password" type="password" autocomplete="new-password" :placeholder="item.credentialConfigured ? '已保存，留空不修改' : '请输入密码'"></label>
                  <label>Schema<input v-model="connectionDrafts[item.id].schema" autocomplete="off"></label>
                  <label>最大连接数<input v-model.number="connectionDrafts[item.id].maximumPoolSize" type="number" min="1"></label>
                  <label>最小空闲连接<input v-model.number="connectionDrafts[item.id].minimumIdle" type="number" min="0"></label>
                </details>
                <div class="settings-db-actions">
                  <button type="button" :disabled="testing === item.id" @click="testConnection(item)">{{ testing === item.id ? '正在测试…' : '仅测试连接' }}</button>
                  <button type="button" class="is-primary" :disabled="testing === item.id || saving === `database:${item.id}` || !connectionDrafts[item.id].enabled" @click="testAndApplyDatabase(item)">{{ testing === item.id ? '正在测试…' : saving === `database:${item.id}` ? '正在应用…' : '测试并立即应用' }}</button>
                  <span v-if="connectionResults[item.id]" :data-state="connectionResults[item.id].status.toLowerCase()">{{ connectionResults[item.id].message }} · {{ connectionResults[item.id].durationMs }} ms</span>
                </div>
              </article>
            </details>
          </div>
          <p v-if="message" class="settings-message">{{ message }}</p>
        </section>

        <section v-else-if="activeTab === 'logs'" class="settings-section settings-log-section">
          <header>
            <div><h3>错误日志</h3><p>接口出现错误时，可用页面提示的错误编号在这里定位完整原因。</p></div>
            <small>{{ logSnapshot?.updatedAt ? `更新于 ${new Date(logSnapshot.updatedAt).toLocaleString()}` : '当前服务' }}</small>
          </header>
          <div class="settings-log-toolbar">
            <div role="group" aria-label="日志范围">
              <button type="button" :class="{ active: logLevel === 'ERROR' }" @click="logLevel = 'ERROR'; loadLogs()">错误</button>
              <button type="button" :class="{ active: logLevel === 'ALL' }" @click="logLevel = 'ALL'; loadLogs()">全部日志</button>
            </div>
            <input v-model="logSearch" aria-label="搜索错误日志" placeholder="搜索错误编号、指标编码或异常内容">
            <button type="button" :disabled="logLoading" @click="loadLogs">{{ logLoading ? '读取中…' : '刷新' }}</button>
            <button type="button" :disabled="!visibleLogContent" @click="copyLogs">复制</button>
          </div>
          <dl v-if="logSnapshot" class="settings-log-meta">
            <div><dt>日志文件</dt><dd>{{ logSnapshot.path }}</dd></div>
            <div><dt>当前显示</dt><dd>{{ logSnapshot.lineCount }} 行<template v-if="logSnapshot.truncated"> · 仅显示末尾内容</template></dd></div>
          </dl>
          <p v-if="logMessage" class="settings-message">{{ logMessage }}</p>
          <p v-if="logLoading && !logSnapshot" class="settings-state">正在读取服务日志…</p>
          <pre v-else-if="visibleLogContent" class="settings-log-content">{{ visibleLogContent }}</pre>
          <div v-else class="settings-log-empty">
            <strong>暂时没有可显示的错误</strong>
            <p>出现错误后刷新这里；页面若给出 ERR_ 开头的编号，可直接粘贴到搜索框。</p>
          </div>
        </section>
      </main>
    </aside>
  </div>
</template>
