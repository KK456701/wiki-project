<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'

import {
  exportHospitalKnowledgePackage,
  listHospitalKnowledgeDrafts,
  loadHospitalKnowledgeDraft,
  type HospitalKnowledgeDraft,
} from '../api/agent'
import { useAgentStore } from '../stores/agent'

const store = useAgentStore()
const drafts = ref<HospitalKnowledgeDraft[]>([])
const selected = ref<HospitalKnowledgeDraft | null>(null)
const hospitalId = ref('')
const packageAvailable = ref(false)
const loading = ref(false)
const exporting = ref(false)
const error = ref('')
const copied = ref('')

const pendingCount = computed(() => drafts.value.filter((item) => item.reviewStatus === 'PENDING_REVIEW').length)

onMounted(refresh)

async function refresh() {
  loading.value = true
  error.value = ''
  try {
    if (!store.capabilities) await store.refreshCapabilities()
    const response = await listHospitalKnowledgeDrafts(store.token)
    hospitalId.value = response.hospitalId
    packageAvailable.value = response.packageAvailable
    drafts.value = response.items
    if (selected.value) {
      selected.value = drafts.value.find((item) => item.draftId === selected.value?.draftId) || null
    }
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '读取待审批口径失败'
  } finally {
    loading.value = false
  }
}

async function openDraft(draft: HospitalKnowledgeDraft) {
  loading.value = true
  error.value = ''
  try {
    selected.value = await loadHospitalKnowledgeDraft(store.token, draft.draftId)
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '读取草稿详情失败'
  } finally {
    loading.value = false
  }
}

async function copySql(kind: string, sql: string) {
  await navigator.clipboard.writeText(sql)
  copied.value = kind
  window.setTimeout(() => { if (copied.value === kind) copied.value = '' }, 1600)
}

async function exportPackage() {
  if (exporting.value) return
  exporting.value = true
  error.value = ''
  try {
    await exportHospitalKnowledgePackage(store.token)
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '回收医院知识库失败'
  } finally {
    exporting.value = false
  }
}

function requirement(draft: HospitalKnowledgeDraft): string {
  const request = draft.changeRequest || {}
  const proposal = request.changeProposal as Record<string, unknown> | undefined
  return String(request.diffSummary || proposal?.requirements || '未单独登记修改说明')
}

function resultValue(source: unknown, type: 'numerator' | 'denominator' | 'result'): string {
  const row = Array.isArray(source) && source.length && typeof source[0] === 'object'
    ? source[0] as Record<string, unknown> : {}
  const entry = Object.entries(row).find(([key]) => type === 'numerator'
    ? key.startsWith('分子') : type === 'denominator' ? key.startsWith('分母')
      : key === '监测情况' || key.includes('结果值'))
  return entry ? String(entry[1]) : '—'
}
</script>

<template>
  <main class="knowledge-review-page">
    <header class="knowledge-review-topbar">
      <div><span class="knowledge-review-kicker">实施管理工作台</span><h1>知识库回收与审批</h1><p>查看影子试跑通过的医院候选 SQL，或打包回收当前医院独立知识目录。</p></div>
      <div class="knowledge-review-actions"><RouterLink to="/">返回指标 Agent</RouterLink><button type="button" :disabled="exporting || !packageAvailable" :title="packageAvailable ? '下载整个医院知识库目录的 ZIP 副本，不会删除服务器文件' : '当前医院尚无可回收的草稿资料'" @click="exportPackage">{{ exporting ? '正在打包…' : '回收医院知识库 ZIP' }}</button></div>
    </header>

    <section class="knowledge-review-summary"><div><span>当前医院</span><strong>{{ hospitalId || '—' }}</strong></div><div><span>待审批候选</span><strong>{{ pendingCount }}</strong></div><div><span>正式生效</span><strong>0</strong><small>本阶段不支持发布</small></div><button type="button" :disabled="loading" @click="refresh">刷新列表</button></section>
    <p v-if="error" class="knowledge-review-error">{{ error }}</p>

    <section class="knowledge-review-workspace">
      <aside class="knowledge-draft-list">
        <header><strong>待审批 SQL</strong><span>{{ drafts.length }} 个草稿</span></header>
        <button v-for="draft in drafts" :key="draft.draftId" type="button" :class="{ active: selected?.draftId === draft.draftId }" @click="openDraft(draft)">
          <span><b>{{ draft.ruleId }}</b><em>待审批</em></span><strong>{{ draft.profileId }}</strong><small>{{ draft.changeLayer === 'SOURCE_EXTRACT' ? '源表抽取 SQL' : '目标表概览 SQL' }} · {{ draft.createdAt }}</small>
        </button>
        <p v-if="!loading && !drafts.length">当前医院还没有待审批草稿。</p>
      </aside>

      <article class="knowledge-draft-detail">
        <template v-if="selected">
          <header><div><span>待审批医院草稿</span><h2>{{ selected.ruleId }} · {{ selected.profileId }}</h2><p>{{ selected.draftId }}</p></div><em>未发布，不影响正式计算</em></header>
          <section class="knowledge-change-note"><strong>医院修改要求</strong><p>{{ requirement(selected) }}</p></section>
          <section class="knowledge-change-note"><strong>本次修改说明</strong><dl><div><dt>问题说明</dt><dd>{{ selected.issueSummary || '未填写' }}</dd></div><div><dt>本次修改</dt><dd>{{ selected.changeSummary || '未填写' }}</dd></div><div><dt>预期影响</dt><dd>{{ selected.expectedImpact || '未填写' }}</dd></div><div><dt>影子验证结论</dt><dd>{{ selected.verificationSummary || '未填写' }}</dd></div></dl></section>
          <section class="knowledge-trial-result"><header><strong>影子试跑结果</strong><span :data-pass="selected.trialPassed">{{ selected.trialPassed ? '验收通过' : '验收未通过' }}</span></header><div><span>对比项</span><span>正式结果</span><span>候选结果</span></div><div><strong>分子</strong><span>{{ resultValue(selected.shadowTrial.originalResult, 'numerator') }}</span><span>{{ resultValue(selected.shadowTrial.candidateResult, 'numerator') }}</span></div><div><strong>分母</strong><span>{{ resultValue(selected.shadowTrial.originalResult, 'denominator') }}</span><span>{{ resultValue(selected.shadowTrial.candidateResult, 'denominator') }}</span></div><div><strong>结果值</strong><span>{{ resultValue(selected.shadowTrial.originalResult, 'result') }}</span><span>{{ resultValue(selected.shadowTrial.candidateResult, 'result') }}</span></div></section>
          <p class="knowledge-review-note"><strong>这里保存的是知识库模板 SQL。</strong>不会把现场数据库名或 <code>[WINDBA_GN]</code> Schema 固化进口径；诊断页“可复制到 Navicat”的版本才会按当前医院连接补齐这些前缀。</p>
          <details class="knowledge-sql"><summary><span>当前正式{{ selected.changeLayer === 'SOURCE_EXTRACT' ? '源表抽取 SQL' : '目标表概览 SQL' }}（知识库模板）</span><button type="button" @click.prevent.stop="copySql('original', selected.originalSql)">{{ copied === 'original' ? '已复制' : '复制 SQL' }}</button></summary><pre>{{ selected.originalSql }}</pre></details>
          <details class="knowledge-sql"><summary><span>待审批候选{{ selected.changeLayer === 'SOURCE_EXTRACT' ? '源表抽取 SQL' : '目标表概览 SQL' }}（知识库模板）</span><button type="button" @click.prevent.stop="copySql('candidate', selected.candidateSql)">{{ copied === 'candidate' ? '已复制' : '复制 SQL' }}</button></summary><pre>{{ selected.candidateSql }}</pre></details>
          <details class="knowledge-sql"><summary><span>技术验收证据</span></summary><pre>{{ JSON.stringify(selected.shadowTrial, null, 2) }}</pre></details>
          <p class="knowledge-review-note">当前页面只支持查看和回收。正式审批、激活医院口径和回滚尚未开放。</p>
        </template>
        <div v-else class="knowledge-review-empty"><strong>选择一个待审批草稿</strong><p>可查看原 SQL、候选 SQL、修改要求和影子对账证据。</p></div>
      </article>
    </section>
  </main>
</template>
