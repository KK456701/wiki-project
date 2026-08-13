<script setup lang="ts">
import { computed, reactive } from 'vue'

import type { BatchIndicatorResult } from '../stores/agent'
import {
  fetchEffectiveRule,
  fetchIndicatorDetails,
  type EffectiveRule,
  type IndicatorDetailResult,
} from '../api/agent'
import IndicatorDataFlowPanel from './IndicatorDataFlowPanel.vue'
import MetricDetailRenderer from './MetricDetailRenderer.vue'
import { formatIndicatorFailure } from '../utils/indicatorFailure'

const props = defineProps<{
  results: BatchIndicatorResult[]
  token: string
  modelId?: string
}>()

type TabKey = 'caliber' | 'flow' | 'detail'
type DetailGroup = string

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
  const implemented = group.items.filter((item) => item.errorCode !== 'PROFILE_NOT_IMPLEMENTED')
  if (!implemented.length) return 'NOT_IMPLEMENTED'
  if (implemented.some((item) => item.status === 'SUCCESS')) return 'SUCCESS'
  if (implemented.every((item) => item.status === 'NO_SAMPLE')) return 'NO_SAMPLE'
  return implemented[0]?.status ?? 'FAILED'
}

/** 口径 / 数据链路 / 明细面板下沉到每个口径，状态按 ruleId + profileId 缓存 */
function stateOf(item: BatchIndicatorResult): CardState {
  const key = `${item.ruleId}::${item.profileId || 'default'}`
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
  if (status === 'NOT_IMPLEMENTED') return '未实现'
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

/** 口径面板展示业务定义和计算规则；SQL统一移到数据链路节点。 */
const caliberFields: Array<[string, string]> = [
  ['definition', '指标定义'],
  ['caliber', '统计口径'],
  ['numeratorRule', '分子口径'],
  ['denominatorRule', '分母口径'],
  ['formula', '计算公式'],
  ['resultUnit', '结果单位'],
  ['significance', '监测意义'],
  ['dataSource', '数据来源'],
]

function ruleText(rule: EffectiveRule | undefined, key: string): string {
  const value = rule?.[key]
  if (value === undefined || value === null) return ''
  return String(value).trim()
}

async function toggleTab(item: BatchIndicatorResult, tab: TabKey) {
  const state = stateOf(item)
  if (state.activeTab === tab) {
    state.activeTab = ''
    return
  }
  state.activeTab = tab
  if (tab === 'caliber' || tab === 'flow') {
    await loadRule(item, state)
  } else {
    await loadDetail(item, state, state.detailGroup)
  }
}

async function loadRule(item: BatchIndicatorResult, state: CardState) {
  if (state.rule || state.ruleLoading) return
  state.ruleLoading = true
  state.ruleError = ''
  try {
    // 按口径变体读取，让同一指标的每个口径各自返回自己的口径 / 核算方式
    state.rule = await fetchEffectiveRule(
      props.token, item.ruleId, item.profileId, item.statStart, item.statEnd)
  } catch (error) {
    state.ruleError = error instanceof Error ? error.message : '口径读取失败。'
  } finally {
    state.ruleLoading = false
  }
}

async function switchDetailGroup(item: BatchIndicatorResult, detailGroup: DetailGroup) {
  const state = stateOf(item)
  state.detailGroup = detailGroup
  await loadDetail(item, state, detailGroup)
}

type DetailKind =
  'COUNT_RATIO' | 'SUM_CONTRIBUTION' | 'MEDIAN_SAMPLE' | 'DUAL_SOURCE' | 'RATE_COMPARISON'

function groupKind(group: CardGroup): DetailKind {
  return (group.items.find((item) => item.detailKind)?.detailKind || 'COUNT_RATIO') as DetailKind
}

type ResultOutcome = 'reached' | 'not_reached' | 'pending' | 'no_sample' | 'failed'

function numeric(value: unknown): number | null {
  if (typeof value === 'number' && Number.isFinite(value)) return value
  if (typeof value !== 'string') return null
  const parsed = Number(value.replace(/[%倍分钟小时天]/g, '').trim())
  return Number.isFinite(parsed) ? parsed : null
}

function itemOutcome(item: BatchIndicatorResult): ResultOutcome {
  if (item.status === 'FAILED') return 'failed'
  if (item.status === 'NO_SAMPLE') return 'no_sample'
  const value = numeric(item.resultValue)
  const target = numeric(item.targetValue)
  if (value === null || target === null || !item.targetDirection) return 'pending'
  const direction = item.targetDirection.replace(/\s/g, '')
  const reached = direction.includes('<')
    ? (direction.includes('=') ? value <= target : value < target)
    : direction.includes('>')
      ? (direction.includes('=') ? value >= target : value > target)
      : value === target
  return reached ? 'reached' : 'not_reached'
}

function outcomeLabel(item: BatchIndicatorResult): string {
  if (item.errorCode === 'PROFILE_NOT_IMPLEMENTED') return '未实现'
  const value = itemOutcome(item)
  if (value === 'reached') return '达标'
  if (value === 'not_reached') return '未达标'
  if (value === 'pending') return '待确认'
  if (value === 'no_sample') return '无样本'
  return '计算失败'
}

function qualityLabel(item: BatchIndicatorResult): string {
  if (item.errorCode === 'PROFILE_NOT_IMPLEMENTED') return '未实现'
  if (item.status === 'FAILED') return '异常'
  if (item.status === 'NO_SAMPLE') return '无可用样本'
  if (item.dataFreshness === 'extraction_failed_stale') return '旧快照'
  if (item.qualityStatus
    && !['NORMAL', 'OK', 'PASS', 'SUCCESS', '正常'].includes(item.qualityStatus.toUpperCase())) {
    return item.qualityStatus
  }
  return '正常'
}

function profileName(item: BatchIndicatorResult): string {
  return item.profileLabel || (item.profileId ? item.profileId : '推荐方案（公版）')
}

function isOfficial(item: BatchIndicatorResult): boolean {
  return !item.profileId || /公版|推荐方案|默认/.test(profileName(item))
}

function recommendedItem(group: CardGroup): BatchIndicatorResult {
  const successful = group.items.filter((item) => item.status === 'SUCCESS')
  const officialSuccess = successful.find(isOfficial)
  if (officialSuccess) return officialSuccess
  if (successful.length) return successful[0]
  return group.items.find(isOfficial) || group.items[0]
}

function recommendation(group: CardGroup): string {
  const chosen = recommendedItem(group)
  if (group.items.length > 1) {
    if (!isOfficial(chosen) && chosen.status === 'SUCCESS') {
      return `建议优先核查“${profileName(chosen)}”的可用结果；公版口径当前无法正常核算。该建议仅用于排查，系统没有自动替换公版口径，正式采用前需业务负责人确认。`
    }
    return `建议以“${profileName(chosen)}”作为本次主视图，其他口径保留作对照。不同口径的结果不得混算，切换正式口径前需人工确认。`
  }
  if (chosen.status === 'FAILED') {
    return formatIndicatorFailure(chosen.errorCode, chosen.errorMessage)
  }
  if (chosen.status === 'NO_SAMPLE') {
    return '本周期没有可用样本，这属于无法计算，不代表未达标。建议确认统计窗口、源表采集覆盖和对应业务模块是否实际启用。'
  }
  if (chosen.dataFreshness === 'extraction_failed_stale') {
    return '本次结果使用旧快照，不能直接作为正式结论。建议先恢复数据抽取并重跑，再依据新批次结果处置。'
  }
  if (itemOutcome(chosen) === 'not_reached') {
    return '结果未达到当前目标。建议先核对已绑定明细是否反映真实业务，再制定改善措施；不要仅凭总数判断为数据问题。'
  }
  if (itemOutcome(chosen) === 'pending') {
    return '结果已固化，但目标值或达标方向不足以自动判定。建议由业务负责人确认目标口径后再形成结论。'
  }
  return '结果达到当前目标且批次事实可用。建议保留本次报告与明细快照，按周期持续观察变化。'
}

function targetDisplay(item: BatchIndicatorResult): string {
  if (item.targetValue === undefined || item.targetValue === null) return '待确认'
  const unit = item.unit === 'percentage' || item.unit === 'percent'
    ? '%' : item.unit === 'ratio' ? ' 倍' : ''
  return `${item.targetDirection || ''}${item.targetValue}${unit}`
}

function medianDisplay(item: BatchIndicatorResult): string {
  if (item.resultValue === undefined || item.resultValue === null) return '—'
  const value = Number(item.resultValue)
  return `${Number.isInteger(value) ? value : value.toFixed(2)} 分钟`
}

function sampleCount(item: BatchIndicatorResult): number | string {
  if (item.sampleCount !== undefined) return item.sampleCount
  const parsed = item.calculationDisplay?.match(/n\s*=\s*(\d+)/i)?.[1]
  if (parsed) return Number(parsed)
  return item.status === 'NO_SAMPLE' ? 0 : '—'
}

function rateParts(item: BatchIndicatorResult): [string, string] {
  const normalized = (item.calculationDisplay || '').replace('：', ':')
  const [left, right] = normalized.split(':', 2).map((part) => part.trim())
  return [left || '—', right || '—']
}

async function loadDetail(
  item: BatchIndicatorResult,
  state: CardState,
  detailGroup: DetailGroup,
  page = 1,
) {
  if (state.details[detailGroup]?.page === page) return
  if (state.detailLoading) return
  if (!item.batchRunId || !item.statStart || !item.statEnd) {
    state.detailError = '缺少批次运行上下文，无法查询与原卡片绑定的明细。'
    return
  }
  state.detailLoading = true
  state.detailError = ''
  try {
    state.details[detailGroup] = await fetchIndicatorDetails(
      props.token, item.ruleId, detailGroup, item.batchRunId,
      item.statStart, item.statEnd, item.profileId, page, 50)
  } catch (error) {
    state.detailError = error instanceof Error ? error.message : '明细查询失败。'
  } finally {
    state.detailLoading = false
  }
}

async function changeDetailPage(item: BatchIndicatorResult, state: CardState, page: number) {
  await loadDetail(item, state, state.detailGroup, page)
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

      <div class="indicator-profile-table" role="table" :aria-label="`${group.ruleName}口径结果`">
        <div
          class="indicator-profile-row indicator-profile-columns"
          :data-kind="groupKind(group)"
          role="row"
        >
          <span role="columnheader">口径方案</span>
          <template v-if="groupKind(group) === 'MEDIAN_SAMPLE'">
            <span role="columnheader">中位数</span>
            <span role="columnheader">有效样本</span>
          </template>
          <template v-else-if="groupKind(group) === 'DUAL_SOURCE'">
            <span role="columnheader">实际开展</span>
            <span role="columnheader">备案目录</span>
            <span role="columnheader">开展率</span>
          </template>
          <template v-else-if="groupKind(group) === 'RATE_COMPARISON'">
            <span role="columnheader">四级手术率 A/B</span>
            <span role="columnheader">三级手术率 C/D</span>
            <span role="columnheader">两率对比</span>
          </template>
          <template v-else-if="groupKind(group) === 'SUM_CONTRIBUTION'">
            <span role="columnheader">成功贡献值</span>
            <span role="columnheader">抢救总量</span>
            <span role="columnheader">结果值</span>
          </template>
          <template v-else>
            <span role="columnheader">分子</span>
            <span role="columnheader">分母</span>
            <span role="columnheader">结果值</span>
          </template>
          <span role="columnheader">达标</span>
          <span role="columnheader">数据质量</span>
          <span role="columnheader">操作</span>
        </div>
        <template v-for="item in group.items" :key="item.profileId || 'default'">
          <div
            class="indicator-profile-row"
            :class="{ recommended: recommendedItem(group) === item }"
            :data-kind="groupKind(group)"
            role="row"
          >
            <div class="indicator-profile-name" role="cell">
              <strong>{{ profileName(item) }}</strong>
              <em v-if="recommendedItem(group) === item && !isOfficial(item)">系统候选建议</em>
              <em v-else-if="isOfficial(item)">公版主口径</em>
              <small v-if="statRange(item)">{{ statRange(item) }}</small>
            </div>
            <template v-if="groupKind(group) === 'MEDIAN_SAMPLE'">
              <strong class="profile-result-value" role="cell">{{ medianDisplay(item) }}</strong>
              <strong role="cell">{{ sampleCount(item) }}</strong>
            </template>
            <template v-else-if="groupKind(group) === 'RATE_COMPARISON'">
              <strong role="cell">{{ rateParts(item)[0] }}</strong>
              <strong role="cell">{{ rateParts(item)[1] }}</strong>
              <strong class="profile-result-value" role="cell">
                {{ item.calculationDisplay || '—' }}
              </strong>
            </template>
            <template v-else>
              <strong role="cell">{{ item.numeratorCount ?? '—' }}</strong>
              <strong role="cell">{{ item.denominatorCount ?? '—' }}</strong>
              <strong class="profile-result-value" role="cell">{{ formatValue(item) }}</strong>
            </template>
            <span class="profile-outcome" :data-outcome="itemOutcome(item)" role="cell">
              {{ outcomeLabel(item) }}
            </span>
            <span class="profile-quality" :data-status="item.status" role="cell">
              {{ qualityLabel(item) }}
            </span>
            <div class="indicator-row-actions" role="cell">
              <button
                type="button"
                :class="{ active: stateOf(item).activeTab === 'caliber' }"
                @click="toggleTab(item, 'caliber')"
              >口径</button>
              <button
                type="button"
                :class="{ active: stateOf(item).activeTab === 'flow' }"
                @click="toggleTab(item, 'flow')"
              >数据链路</button>
              <button
                type="button"
                :class="{ active: stateOf(item).activeTab === 'detail' }"
                @click="toggleTab(item, 'detail')"
              >明细</button>
            </div>
          </div>

          <div v-if="stateOf(item).activeTab" class="indicator-profile-panel">
            <header>
              <strong>{{ profileName(item) }}</strong>
              <span>目标 {{ targetDisplay(item) }}</span>
            </header>

            <!-- 指标口径 -->
            <div v-if="stateOf(item).activeTab === 'caliber'" class="indicator-panel">
              <p v-if="stateOf(item).ruleLoading" class="indicator-loading">正在读取本院生效口径…</p>
              <p v-else-if="stateOf(item).ruleError" class="indicator-error">{{ stateOf(item).ruleError }}</p>
              <dl v-else class="indicator-fields">
                <template v-for="[key, label] in caliberFields" :key="key">
                  <template v-if="ruleText(stateOf(item).rule, key)">
                    <dt>{{ label }}</dt>
                    <dd>{{ ruleText(stateOf(item).rule, key) }}</dd>
                  </template>
                </template>
              </dl>
            </div>

            <!-- 数据链路 -->
            <div v-if="stateOf(item).activeTab === 'flow'" class="indicator-panel">
              <p v-if="stateOf(item).ruleLoading" class="indicator-loading">正在生成数据链路…</p>
              <p v-else-if="stateOf(item).ruleError" class="indicator-error">{{ stateOf(item).ruleError }}</p>
              <IndicatorDataFlowPanel
                v-else
                :flow="stateOf(item).rule?.dataFlow"
                :token="token"
                :rule-id="item.ruleId"
                :profile-id="item.profileId"
                :stat-start="item.statStart"
                :stat-end="item.statEnd"
              />
            </div>

            <!-- 明细 -->
            <div v-if="stateOf(item).activeTab === 'detail'" class="indicator-panel">
              <MetricDetailRenderer
                :kind="item.detailKind"
                :detail="stateOf(item).details[stateOf(item).detailGroup]"
                :group="stateOf(item).detailGroup"
                :loading="stateOf(item).detailLoading"
                :error="stateOf(item).detailError"
                @group="switchDetailGroup(item, $event)"
                @page="changeDetailPage(item, stateOf(item), $event)"
              />
            </div>
          </div>
        </template>
      </div>

      <p v-if="group.items.some((item) => item.calculationDisplay)" class="indicator-calc-summary">
        {{ recommendedItem(group).calculationDisplay }}
      </p>
      <aside class="indicator-ai-advice" :data-status="groupStatus(group)">
        <strong>系统建议 / 口径使用提示</strong>
        <p>{{ recommendation(group) }}</p>
      </aside>
    </article>
  </section>
</template>
