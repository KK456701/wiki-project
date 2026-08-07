<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import {
  loadRuntimeSettings,
  testRuntimeConnection,
  type AgentModel,
  type ConnectionTestResult,
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

const activeTab = ref<'models' | 'databases' | 'mcp'>('models')
const settings = ref<RuntimeSettings | null>(null)
const loading = ref(true)
const error = ref('')
const testing = ref('')
const connectionResults = ref<Record<string, ConnectionTestResult>>({})

const modelItems = computed(() => settings.value?.models?.length ? settings.value.models : props.models)

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    settings.value = await loadRuntimeSettings(props.token)
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '运行配置读取失败。'
  } finally {
    loading.value = false
  }
}

async function testConnection(item: RuntimeDatabaseSetting) {
  testing.value = item.id
  try {
    connectionResults.value[item.id] = await testRuntimeConnection(props.token, item.id)
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
          <p>切换模型，核对数据库与内嵌 MCP 的当前状态。</p>
        </div>
        <button type="button" class="drawer-close" aria-label="关闭设置" @click="emit('close')">×</button>
      </header>

      <nav class="settings-tabs" aria-label="设置分类">
        <button type="button" :class="{ active: activeTab === 'models' }" @click="activeTab = 'models'">模型</button>
        <button type="button" :class="{ active: activeTab === 'databases' }" @click="activeTab = 'databases'">数据库</button>
        <button type="button" :class="{ active: activeTab === 'mcp' }" @click="activeTab = 'mcp'">MCP</button>
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
          <div class="settings-config-note"><strong>API Key 配置</strong><code>DEEPSEEK_API_KEY</code><code>DASHSCOPE_API_KEY</code><p>修改服务器环境变量后重启 Java 服务生效，页面不会接收或保存 Key。</p></div>
        </section>

        <section v-else-if="activeTab === 'databases'" class="settings-section">
          <header><div><span>服务器运行时</span><h3>数据库连接</h3></div><small>密码不回显</small></header>
          <p class="settings-explain">业务库和真实库属于正式计算链路；Oracle 是新增加的独立扩展连接，目前不会自动替换正式链路。</p>
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
            <details><summary>环境变量</summary><code v-for="name in item.environmentVariables" :key="name">{{ name }}</code></details>
          </article>
        </section>

        <section v-else class="settings-section">
          <header><div><span>同进程工具服务</span><h3>{{ settings?.mcp.label }}</h3></div><small>无需 8080 sidecar</small></header>
          <div class="settings-mcp-rail">
            <div><span>应用</span><strong>Spring Boot :8765</strong></div><i aria-hidden="true"></i><div><span>MCP端点</span><strong>{{ settings?.mcp.endpoint }}</strong></div><i aria-hidden="true"></i><div><span>数据库工具</span><strong>{{ settings?.mcp.tools.length }} 个已注册</strong></div>
          </div>
          <ul class="settings-tool-list"><li v-for="tool in settings?.mcp.tools || []" :key="tool"><code>{{ tool }}</code></li></ul>
          <div class="settings-config-note"><strong>运行参数</strong><span>超时 {{ settings?.mcp.timeoutSeconds }} 秒</span><code v-for="name in settings?.mcp.environmentVariables || []" :key="name">{{ name }}</code><p>默认使用应用自身的 <code>/mcp</code>；只有显式设置外部地址时才会覆盖。</p></div>
        </section>
      </main>
    </aside>
  </div>
</template>
