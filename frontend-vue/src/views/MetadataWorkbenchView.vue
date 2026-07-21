<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import {
  loadMetadataOverview,
  syncMetadata,
  type MetadataOverview,
} from '../api/agent'
import { useAgentStore } from '../stores/agent'

const store = useAgentStore()
const router = useRouter()
const loading = ref(false)
const syncing = ref(false)
const error = ref('')
const overview = ref<MetadataOverview | null>(null)

const changeGroups = computed(() => {
  const groups = new Map<string, number>()
  for (const item of overview.value?.changes || []) {
    groups.set(item.change_type, (groups.get(item.change_type) || 0) + 1)
  }
  return [...groups.entries()].map(([type, count]) => ({ type, count }))
})

onMounted(async () => {
  if (!store.isAuthenticated || !store.user) {
    await router.replace('/')
    return
  }
  await refresh()
})

async function refresh() {
  if (!store.user) return
  loading.value = true
  error.value = ''
  try {
    overview.value = await loadMetadataOverview(store.token, store.user.hospitalId)
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '元数据概览加载失败。'
  } finally {
    loading.value = false
  }
}

async function synchronize() {
  if (!store.user || syncing.value) return
  if (!window.confirm('将通过 DBHub 读取已配置业务库的结构，并更新本院元数据快照。确认继续吗？')) return
  syncing.value = true
  error.value = ''
  try {
    overview.value = await syncMetadata(store.token, store.user.hospitalId)
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '数据库元数据同步失败。'
  } finally {
    syncing.value = false
  }
}

function changeLabel(type: string) {
  return ({
    table_added: '新增表', table_deleted: '删除表', column_added: '新增字段',
    column_deleted: '删除字段', column_type_changed: '字段类型变化',
    column_nullable_changed: '可空性变化',
  } as Record<string, string>)[type] || type
}

function time(value?: string) {
  return value ? value.replace('T', ' ').slice(0, 19) : '尚未同步'
}
</script>

<template>
  <main class="metadata-shell">
    <header class="metadata-head">
      <div>
        <p class="eyebrow">DBHub · Metadata Contract</p>
        <h1>数据库元数据工作台</h1>
        <p>只读采集业务库结构，核对指标字段映射；Java 不直连 SQL Server。</p>
      </div>
      <div class="metadata-head-actions">
        <RouterLink class="quiet-button" to="/">返回对话</RouterLink>
        <RouterLink class="quiet-button" to="/runs">运行观察</RouterLink>
        <RouterLink class="quiet-button" to="/terminology">医学术语</RouterLink>
        <RouterLink class="quiet-button" to="/monitoring">指标监控</RouterLink>
        <button class="primary-button" type="button" :disabled="syncing" @click="synchronize">
          {{ syncing ? '正在经 DBHub 同步…' : '同步元数据' }}
        </button>
      </div>
    </header>

    <section class="metadata-context">
      <div><span>当前医院</span><strong>{{ store.user?.hospitalId }}</strong></div>
      <div><span>业务库</span><strong>{{ overview?.db_name || '读取配置中' }}</strong></div>
      <div><span>采集边界</span><strong>全表目录 + 映射字段</strong></div>
      <button type="button" :disabled="loading" @click="refresh">{{ loading ? '加载中…' : '刷新概览' }}</button>
    </section>

    <p v-if="error" class="runs-error metadata-error">{{ error }}</p>

    <section class="metadata-metrics">
      <article><span>数据库表</span><strong>{{ overview?.table_count || 0 }}</strong><small>INFORMATION_SCHEMA.TABLES</small></article>
      <article><span>映射字段</span><strong>{{ overview?.column_count || 0 }}</strong><small>仅采集指标依赖表字段</small></article>
      <article><span>结构变化</span><strong>{{ overview?.changes.length || 0 }}</strong><small>相对上一次快照</small></article>
      <article><span>受影响指标</span><strong>{{ overview?.affected_rules.length || 0 }}</strong><small>按字段映射确定性关联</small></article>
    </section>

    <section class="metadata-status" :data-ready="overview?.has_snapshot">
      <div>
        <p class="eyebrow">Latest snapshot</p>
        <h2>{{ overview?.has_snapshot ? '本院元数据快照已就绪' : '尚未建立本院元数据快照' }}</h2>
        <p>{{ overview?.has_snapshot ? `最近同步于 ${time(overview?.synced_at)}` : '首次同步后才会显示结构变化和受影响指标。' }}</p>
      </div>
      <dl>
        <div><dt>批次</dt><dd>{{ overview?.batch_id || '-' }}</dd></div>
        <div><dt>来源</dt><dd>{{ overview?.metadata_source || 'DBHub' }}</dd></div>
        <div><dt>Trace</dt><dd>{{ overview?.trace_id || '-' }}</dd></div>
      </dl>
    </section>

    <section v-if="changeGroups.length" class="metadata-change-strip">
      <span v-for="item in changeGroups" :key="item.type">
        <strong>{{ item.count }}</strong>{{ changeLabel(item.type) }}
      </span>
    </section>

    <section class="metadata-grid">
      <article class="metadata-panel">
        <header><div><p class="eyebrow">Schema diff</p><h2>结构变化</h2></div><span>{{ overview?.changes.length || 0 }} 项</span></header>
        <div class="metadata-table-wrap">
          <table>
            <thead><tr><th>类型</th><th>表</th><th>字段</th><th>变化说明</th></tr></thead>
            <tbody>
              <tr v-for="item in overview?.changes || []" :key="`${item.change_type}-${item.table_name}-${item.field_name}`">
                <td><span class="change-badge" :data-type="item.change_type">{{ changeLabel(item.change_type) }}</span></td>
                <td><code>{{ item.table_name || '-' }}</code></td><td><code>{{ item.field_name || '-' }}</code></td><td>{{ item.change_desc }}</td>
              </tr>
              <tr v-if="!overview?.changes.length"><td colspan="4">当前快照没有检测到结构变化。</td></tr>
            </tbody>
          </table>
        </div>
      </article>

      <article class="metadata-panel affected-panel">
        <header><div><p class="eyebrow">Impact map</p><h2>受影响指标</h2></div><span>{{ overview?.affected_rules.length || 0 }} 项</span></header>
        <div class="affected-list">
          <article v-for="item in overview?.affected_rules || []" :key="item.rule_id">
            <strong>{{ item.rule_id }}</strong>
            <p>业务字段：{{ item.business_fields.join('、') || '-' }}</p>
            <small>数据库字段：{{ item.matched_columns.join('、') || '-' }}</small>
          </article>
          <p v-if="!overview?.affected_rules.length">没有指标字段映射受到本次结构变化影响。</p>
        </div>
      </article>
    </section>
  </main>
</template>
