<script setup lang="ts">
import { computed, reactive } from 'vue'

import type { BatchIndicatorResult } from '../stores/agent'
import {
  fetchEffectiveRule,
  fetchIndicatorDetails,
  type EffectiveRule,
  type IndicatorDetailResult,
} from '../api/agent'

const props = defineProps<{
  results: BatchIndicatorResult[]
  token: string
}>()

type TabKey = 'caliber' | 'method' | 'detail'
type DetailGroup = 'numerator' | 'denominator'

interface CardState {
  activeTab: TabKey | ''
  detailGroup: DetailGroup
  rule?: EffectiveRule
  ruleLoading: boolean
  ruleError: string
  details: Partial<Record<DetailGroup, IndicatorDetailResult>>
  detailLoading: boolean
  detailError: string
}

const states = reactive<Record<string, CardState>>({})

/** 同一指标的多个口径合并到一张卡片：按 ruleId 分组，保留到达顺序 */
interface CardGroup {
  ruleId: string
  ruleName: string
  items: BatchIndicatorResult[]
}

const groups = computed<CardGroup[]>(() => {
  const map = new Map<string, CardGroup>()
  for (const item of props.results) {
    const existing = map.get(item.ruleId)
    if (existing) {
      existing.items.push(item)
      if (!existing.ruleName && item.ruleName) existing.ruleName = item.ruleName
    } else {
      map.set(item.ruleId, {
        ruleId: item.ruleId,
        ruleName: item.ruleName || '',
        items: [item],
      })
    }
  }
  return [...map.values()]
})

/** 卡片头部状态：任一口径成功即成功，全部无样本才显示无样本 */
function groupStatus(group: CardGroup): string {
  if (group.items.some((item) => item.status === 'SUCCESS')) return 'SUCCESS'
  if (group.items.every((item) => item.status === 'NO_SAMPLE')) return 'NO_SAMPLE'
  return group.items[0]?.status ?? 'FAILED'
}

/** 口径/明细等面板只依赖 ruleId，状态按指标维度缓存 */
function stateOf(group: CardGroup): CardState {
  const key = group.ruleId
  if (!states[key]) {
    states[key] = {
      activeTab: '',
      detailGroup: 'numerator',
      ruleLoading: false,
      ruleError: '',
      details: {},
      detailLoading: false,
      detailError: '',
    }
  }
  return states[key]
}

function statusLabel(status: string): string {
  if (status === 'SUCCESS') return '计算成功'
  if (status === 'NO_SAMPLE') return '无样本'
  return '计算失败'
}

function formatValue(item: BatchIndicatorResult): string {
  if (item.resultValue === undefined || item.resultValue === null) return '—'
  const value = Number(item.resultValue)
  if (Number.isNaN(value)) return String(item.resultValue)
  const text = Number.isInteger(value) ? String(value) : value.toFixed(2)
  if (item.unit === 'percentage' || item.unit === 'percent') return `${text}%`
  if (item.unit === 'ratio') return `${text} 倍`
  return item.unit ? `${text}${item.unit}` : text
}

function statRange(item: BatchIndicatorResult): string {
  if (!item.statStart || !item.statEnd) return ''
  return `${item.statStart.slice(0, 10)} 至 ${item.statEnd.slice(0, 10)}`
}

/** 口径 / 核算方式面板要展示的知识库字段（按顺序），空值自动跳过 */
const caliberFields: Array<[string, string]> = [
  ['definition', '指标定义'],
  ['caliber', '统计口径'],
  ['numeratorRule', '分子口径'],
  ['denominatorRule', '分母口径'],
  ['significance', '监测意义'],
  ['dataSource', '数据来源'],
]

const methodFields: Array<[string, string]> = [
  ['formula', '计算公式'],
  ['resultUnit', '结果单位'],
]

function ruleText(rule: EffectiveRule | undefined, key: string): string {
  const value = rule?.[key]
  if (value === undefined || value === null) return ''
  return String(value).trim()
}

async function toggleTab(group: CardGroup, tab: TabKey) {
  const state = stateOf(group)
  if (state.activeTab === tab) {
    state.activeTab = ''
    return
  }
  state.activeTab = tab
  if (tab === 'caliber' || tab === 'method') {
    await loadRule(group, state)
  } else {
    await loadDetail(group, state, state.detailGroup)
  }
}

async function loadRule(group: CardGroup, state: CardState) {
  if (state.rule || state.ruleLoading) return
  state.ruleLoading = true
  state.ruleError = ''
  try {
    state.rule = await fetchEffectiveRule(props.token, group.ruleId)
  } catch (error) {
    state.ruleError = error instanceof Error ? error.message : '口径读取失败。'
  } finally {
    state.ruleLoading = false
  }
}

async function switchDetailGroup(group: CardGroup, detailGroup: DetailGroup) {
  const state = stateOf(group)
  state.detailGroup = detailGroup
  await loadDetail(group, state, detailGroup)
}

async function loadDetail(group: CardGroup, state: CardState, detailGroup: DetailGroup) {
  // 不用本地缓存：每次展开/切换都重新请求，后端明细 SQL 也是每次重新生成
  if (state.detailLoading) return
  const timeSource = group.items.find((item) => item.statStart && item.statEnd)
  if (!timeSource?.statStart || !timeSource?.statEnd) {
    state.detailError = '缺少统计区间，无法查询明细。'
    return
  }
  state.detailLoading = true
  state.detailError = ''
  try {
    state.details[detailGroup] = await fetchIndicatorDetails(
      props.token, group.ruleId, detailGroup, timeSource.statStart, timeSource.statEnd)
  } catch (error) {
    state.detailError = error instanceof Error ? error.message : '明细查询失败。'
  } finally {
    state.detailLoading = false
  }
}

function detailColumns(detail: IndicatorDetailResult | undefined): string[] {
  const first = detail?.rows?.[0]
  return first ? Object.keys(first) : []
}

function cellText(row: Record<string, unknown>, column: string): string {
  const value = row[column]
  if (value === undefined || value === null) return ''
  return String(value)
}
</script>

<template>
  <section class="indicator-cards">
    <article
      v-for="group in groups"
      :key="group.ruleId"
      class="indicator-card"
      :data-status="groupStatus(group)"
    >
      <header class="indicator-card-head">
        <div>
          <span>计算结果 · 已验证</span>
          <strong>{{ group.ruleName || group.ruleId }}</strong>
          <code>{{ group.ruleId }}</code>
          <em v-if="group.items.length > 1" class="indicator-profile-label">共 {{ group.items.length }} 种口径</em>
        </div>
        <span class="indicator-status" :data-status="groupStatus(group)">{{ statusLabel(groupStatus(group)) }}</span>
      </header>

      <div
        v-for="item in group.items"
        :key="item.profileId || 'default'"
        class="indicator-caliber-block"
      >
        <p v-if="group.items.length > 1 || item.profileLabel" class="indicator-caliber-head">
          <em class="indicator-profile-label">{{ item.profileLabel || '默认口径' }}</em>
          <span class="indicator-status" :data-status="item.status">{{ statusLabel(item.status) }}</span>
        </p>

        <div class="indicator-result-grid">
          <div class="indicator-result-primary">
            <span>指标值</span>
            <p class="indicator-value">{{ formatValue(item) }}</p>
            <small v-if="statRange(item)">{{ statRange(item) }}</small>
          </div>
          <div class="indicator-result-stat">
            <span>分子</span>
            <strong>{{ item.numeratorCount ?? '—' }}</strong>
            <small>符合条件的患者人次</small>
          </div>
          <div class="indicator-result-stat">
            <span>分母</span>
            <strong>{{ item.denominatorCount ?? '—' }}</strong>
            <small>统计范围内患者人次</small>
          </div>
          <div class="indicator-result-check" :data-status="item.status">
            <span aria-hidden="true">{{ item.status === 'SUCCESS' ? '✓' : item.status === 'NO_SAMPLE' ? '!' : '×' }}</span>
            <div>
              <strong>{{ statusLabel(item.status) }}</strong>
              <small>{{ item.status === 'SUCCESS' ? '结果已通过证据验证' : '请查看本卡片的状态说明' }}</small>
            </div>
          </div>
        </div>

        <div class="indicator-card-body">
          <p v-if="item.calculationDisplay" class="indicator-calc">{{ item.calculationDisplay }}</p>
          <p v-if="item.dataFreshness === 'extraction_failed_stale'" class="indicator-stale-warning">
            ⚠️ 数据抽取失败，本结果基于中间表旧数据，仅供参考
          </p>
          <p v-if="item.errorMessage" class="indicator-error">{{ item.errorMessage }}</p>
        </div>
      </div>

      <div class="indicator-card-actions">
        <button
          type="button"
          :class="{ active: stateOf(group).activeTab === 'caliber' }"
          @click="toggleTab(group, 'caliber')"
        >指标口径</button>
        <button
          type="button"
          :class="{ active: stateOf(group).activeTab === 'method' }"
          @click="toggleTab(group, 'method')"
        >核算方式</button>
        <button
          type="button"
          :class="{ active: stateOf(group).activeTab === 'detail' }"
          @click="toggleTab(group, 'detail')"
        >明细</button>
      </div>

      <!-- 指标口径 -->
      <div v-if="stateOf(group).activeTab === 'caliber'" class="indicator-panel">
        <p v-if="stateOf(group).ruleLoading" class="indicator-loading">正在读取本院生效口径…</p>
        <p v-else-if="stateOf(group).ruleError" class="indicator-error">{{ stateOf(group).ruleError }}</p>
        <dl v-else class="indicator-fields">
          <template v-for="[key, label] in caliberFields" :key="key">
            <template v-if="ruleText(stateOf(group).rule, key)">
              <dt>{{ label }}</dt>
              <dd>{{ ruleText(stateOf(group).rule, key) }}</dd>
            </template>
          </template>
        </dl>
      </div>

      <!-- 核算方式 -->
      <div v-if="stateOf(group).activeTab === 'method'" class="indicator-panel">
        <p v-if="stateOf(group).ruleLoading" class="indicator-loading">正在读取核算方式…</p>
        <p v-else-if="stateOf(group).ruleError" class="indicator-error">{{ stateOf(group).ruleError }}</p>
        <template v-else>
          <dl class="indicator-fields">
            <template v-for="[key, label] in methodFields" :key="key">
              <template v-if="ruleText(stateOf(group).rule, key)">
                <dt>{{ label }}</dt>
                <dd>{{ ruleText(stateOf(group).rule, key) }}</dd>
              </template>
            </template>
            <template v-if="group.items[0]?.calculationDisplay">
              <dt>本次核算</dt>
              <dd>{{ group.items[0].calculationDisplay }}</dd>
            </template>
          </dl>
          <details v-if="ruleText(stateOf(group).rule, 'standardSql')" class="indicator-sql">
            <summary>查看核算 SQL</summary>
            <pre>{{ ruleText(stateOf(group).rule, 'standardSql') }}</pre>
          </details>
        </template>
      </div>

      <!-- 明细 -->
      <div v-if="stateOf(group).activeTab === 'detail'" class="indicator-panel">
        <div class="indicator-detail-groups">
          <button
            type="button"
            :class="{ active: stateOf(group).detailGroup === 'numerator' }"
            @click="switchDetailGroup(group, 'numerator')"
          >分子明细</button>
          <button
            type="button"
            :class="{ active: stateOf(group).detailGroup === 'denominator' }"
            @click="switchDetailGroup(group, 'denominator')"
          >分母明细</button>
        </div>
        <p v-if="stateOf(group).detailLoading" class="indicator-loading">正在查询患者明细（首次查询需生成明细 SQL，可能需要十几秒）…</p>
        <p v-else-if="stateOf(group).detailError" class="indicator-error">{{ stateOf(group).detailError }}</p>
        <template v-else-if="stateOf(group).details[stateOf(group).detailGroup]">
          <p class="indicator-detail-summary">
            共 {{ stateOf(group).details[stateOf(group).detailGroup]!.rowCount }} 条记录
            <template v-if="stateOf(group).details[stateOf(group).detailGroup]!.truncated">（仅展示前 200 条）</template>
          </p>
          <p
            v-if="stateOf(group).details[stateOf(group).detailGroup]!.sqlSource === 'mras_patient_detail'"
            class="indicator-error"
          >⚠ 分子/分母明细 SQL 生成失败，当前展示的是知识库通用患者明细，不区分分子/分母，行数仅供参考。</p>
          <div
            v-if="stateOf(group).details[stateOf(group).detailGroup]!.rows.length"
            class="indicator-detail-table"
          >
            <table>
              <thead>
                <tr>
                  <th v-for="column in detailColumns(stateOf(group).details[stateOf(group).detailGroup])" :key="column">{{ column }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(row, rowIndex) in stateOf(group).details[stateOf(group).detailGroup]!.rows" :key="rowIndex">
                  <td v-for="column in detailColumns(stateOf(group).details[stateOf(group).detailGroup])" :key="column">{{ cellText(row, column) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <p v-else class="indicator-loading">统计区间内没有明细记录。</p>
          <details
            v-if="stateOf(group).details[stateOf(group).detailGroup]!.detailSql"
            class="indicator-sql"
          >
            <summary>查看明细 SQL</summary>
            <pre>{{ stateOf(group).details[stateOf(group).detailGroup]!.detailSql }}</pre>
          </details>
        </template>
      </div>
    </article>
  </section>
</template>
