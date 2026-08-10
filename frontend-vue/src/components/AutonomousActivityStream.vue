<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'

import {
  latestPendingQuestionId,
  projectAutonomousActivities,
  type AutonomousActivityItem,
} from '../domain/autonomousActivity'

const props = defineProps<{
  turns: Array<Record<string, unknown>>
  events: Array<Record<string, unknown>>
  runStatus: string
  modelName: string
  busy?: boolean
}>()

const emit = defineEmits<{
  respond: [answer: string]
  cancel: []
}>()

const answer = ref('')
const now = ref(Date.now())
let clock = 0

const projectedTurns = computed(() => props.turns.map((turn, index) => ({
  key: String(turn.turnId || turn.clientMessageId || index),
  turn,
  activities: projectAutonomousActivities(turn, props.events),
})))

const pendingQuestionId = computed(() => latestPendingQuestionId(props.turns, props.events, props.runStatus))

clock = window.setInterval(() => { now.value = Date.now() }, 500)
onUnmounted(() => window.clearInterval(clock))

function processActivities(items: AutonomousActivityItem[]): AutonomousActivityItem[] {
  return items.filter((item) => ['MODEL', 'TOOL', 'PROCESS'].includes(item.kind))
}

function visibleActivities(items: AutonomousActivityItem[]): AutonomousActivityItem[] {
  return items.filter((item) => !['MODEL', 'TOOL', 'PROCESS'].includes(item.kind))
}

function turnProgress(
  turn: Record<string, unknown>,
  items: AutonomousActivityItem[],
): { label: string, state: 'running' | 'waiting' | 'completed' | 'failed' | 'stopped' } {
  const turnStatus = String(turn.status || '').toUpperCase()
  const running = [...items].reverse().find((item) => item.status === 'RUNNING')
  if (running?.kind === 'TOOL') {
    return { label: `正在调用：${running.toolDisplayName || running.tool || '排查工具'}`, state: 'running' }
  }
  if (running?.kind === 'MODEL') return { label: '思考中', state: 'running' }
  if (running) return { label: running.title || '正在处理', state: 'running' }

  if (turnStatus === 'RUNNING' || turnStatus === 'SENDING' || turnStatus === 'QUEUED') {
    const latest = items.at(-1)
    if (latest?.kind === 'TOOL') return { label: '正在整理工具结果', state: 'running' }
    if (latest?.kind === 'MODEL') return { label: '正在选择下一步', state: 'running' }
    return { label: '正在开始排查', state: 'running' }
  }
  if (turnStatus === 'WAITING_USER') return { label: '等待现场补充', state: 'waiting' }
  if (turnStatus === 'FAILED') return { label: '未完成', state: 'failed' }
  if (turnStatus === 'STOPPED' || turnStatus === 'CANCELLED') return { label: '已停止', state: 'stopped' }
  if (items.some((item) => item.status === 'FAILED')) return { label: '已完成，但有失败', state: 'failed' }
  return { label: '已完成', state: 'completed' }
}

function submitAnswer() {
  const value = answer.value.trim()
  if (!value) return
  emit('respond', value)
  answer.value = ''
}

function turnDuration(turn: Record<string, unknown>): string {
  const started = Date.parse(String(turn.submittedAt || ''))
  const endedAt = String(turn.updatedAt || '')
  const ended = String(turn.status || '') === 'RUNNING' ? now.value : Date.parse(endedAt)
  if (!Number.isFinite(started) || !Number.isFinite(ended)) return '0.0s'
  return `${Math.max(0, (ended - started) / 1000).toFixed(1)}s`
}

function analysisRows(item: AutonomousActivityItem): Array<{ label: string, value: string }> {
  if (!item.analysis) return []
  const rows = [
    ['问题理解', item.analysis.problemUnderstanding],
    ['候选原因', item.analysis.hypotheses.join('；')],
    ['已有依据', item.analysis.evidenceRefs.join('；')],
    ['验证目标', item.analysis.verificationGoal],
    ['为什么调用这个工具', item.analysis.toolChoiceReason],
    ['判断更新', item.analysis.judgementUpdate],
    ['下一步', item.analysis.nextStep],
  ]
  return rows.filter(([, value]) => value).map(([label, value]) => ({ label, value }))
}

function statusText(status: string): string {
  return ({
    RUNNING: '执行中', SUCCEEDED: '已完成', FAILED: '失败', WAITING_USER: '等待回复',
    STOPPED: '已停止', CANCELLED: '已停止', READY: '可继续', COMPLETED: '已完成', QUEUED: '已排队',
  } as Record<string, string>)[status] || status
}

function pretty(value: unknown): string {
  try { return JSON.stringify(value, null, 2) } catch { return String(value ?? '') }
}
</script>

<template>
  <template v-for="(entry, turnIndex) in projectedTurns" :key="entry.key">
    <article class="message is-user">
      <div class="message-avatar">我</div>
      <div class="message-card diagnosis-turn-card">
        <div class="message-head"><strong>实施人员</strong><span>{{ statusText(String(entry.turn.status || '')) }}</span></div>
        <p>{{ entry.turn.userMessage }}</p>
        <p v-if="entry.turn.errorMessage" class="diagnosis-template-warning">{{ entry.turn.errorMessage }}</p>
      </div>
    </article>

    <article v-if="entry.activities.length" class="message is-agent autonomous-process-message">
      <div class="message-avatar">AI</div>
      <div class="message-card diagnosis-turn-card autonomous-stream-card">
        <div class="message-head">
          <strong>{{ modelName }} · 自主排查</strong>
        </div>
        <details v-if="processActivities(entry.activities).length" class="autonomous-process-details">
          <summary>
            <strong>思考过程</strong>
            <span
              class="autonomous-progress-status"
              :data-state="turnProgress(entry.turn, processActivities(entry.activities)).state"
            >
              <i aria-hidden="true"></i>
              {{ turnProgress(entry.turn, processActivities(entry.activities)).label }} · {{ turnDuration(entry.turn) }}
            </span>
          </summary>
          <ol class="autonomous-activity-list">
          <li v-for="item in processActivities(entry.activities)" :key="item.id" :data-kind="item.kind.toLowerCase()" :data-status="item.status.toLowerCase()">
            <details
              v-if="item.kind === 'MODEL'"
              class="autonomous-analysis"
            >
              <summary>
                <span class="autonomous-seq">{{ item.iteration || '—' }}</span>
                <span>
                  <strong>{{ item.status === 'RUNNING' ? '思考中' : '公开分析' }}</strong>
                  <small v-if="item.status === 'RUNNING'">{{ item.summary || '正在理解问题并选择下一步验证方式…' }}</small>
                </span>
                <em>{{ statusText(item.status) }}</em>
              </summary>
              <dl v-if="analysisRows(item).length" class="autonomous-analysis-grid">
                <div v-for="row in analysisRows(item)" :key="row.label"><dt>{{ row.label }}</dt><dd>{{ row.value }}</dd></div>
              </dl>
            </details>

            <template v-else-if="item.kind === 'TOOL'">
              <div class="autonomous-item-head">
                <span class="autonomous-seq">{{ item.iteration || '—' }}</span>
                <strong>{{ item.toolDisplayName || item.tool || '执行工具' }}</strong>
                <em>{{ statusText(item.status) }}<template v-if="item.durationMs !== undefined"> · {{ item.durationMs }}ms</template></em>
              </div>
              <p v-if="item.summary"><strong>观察：</strong>{{ item.summary }}</p>
              <details v-if="item.arguments || item.resultPreview || item.error || item.evidenceId" class="autonomous-tool-details">
                <summary>查看工具输入、结果和证据</summary>
                <section v-if="item.arguments"><strong>输入参数</strong><pre>{{ pretty(item.arguments) }}</pre></section>
                <section v-if="item.resultPreview"><strong>结果预览</strong><pre>{{ pretty(item.resultPreview) }}</pre></section>
                <section v-if="item.error"><strong>执行错误</strong><pre>{{ item.error }}</pre></section>
                <small v-if="item.evidenceId">证据编号：{{ item.evidenceId }}</small>
              </details>
            </template>

            <template v-else>
              <div class="autonomous-item-head"><span class="autonomous-seq">{{ item.iteration || '—' }}</span><strong>{{ item.title }}</strong><em>{{ statusText(item.status) }}</em></div>
              <p>{{ item.summary }}</p>
            </template>
          </li>
          </ol>
        </details>

        <ol v-if="visibleActivities(entry.activities).length" class="autonomous-activity-list autonomous-result-list">
          <li v-for="item in visibleActivities(entry.activities)" :key="item.id" :data-kind="item.kind.toLowerCase()" :data-status="item.status.toLowerCase()">
            <p v-if="item.kind === 'REPLY'" class="autonomous-answer autonomous-direct-reply">{{ item.answer || item.summary }}</p>
            <template v-else>
            <div class="autonomous-item-head">
              <span class="autonomous-seq">{{ item.iteration || '—' }}</span>
              <strong>{{ item.title }}</strong>
              <em>{{ statusText(item.status) }}</em>
            </div>
            <p class="autonomous-answer">{{ item.answer || item.conclusion || item.question || item.summary }}</p>
            <small v-if="item.conclusionLevel">结论等级：{{ item.conclusionLevel }}</small>
            <div v-if="item.kind === 'QUESTION' && item.id === pendingQuestionId" class="autonomous-inline-answer">
              <label :for="`autonomous-answer-${item.seq}`"><strong>填写现场确认结果后继续：</strong></label>
              <textarea :id="`autonomous-answer-${item.seq}`" v-model="answer" rows="3" maxlength="3000" placeholder="填写医院现场确认结果"></textarea>
              <button type="button" class="diagnosis-primary" :disabled="busy || !answer.trim()" @click="submitAnswer">发送回答并继续排查</button>
            </div>
            </template>
          </li>
        </ol>

        <button v-if="runStatus === 'RUNNING' && turnIndex === projectedTurns.length - 1" type="button" class="diagnosis-secondary" :disabled="busy" @click="emit('cancel')">停止本轮</button>
      </div>
    </article>
  </template>
</template>

<style scoped>
.autonomous-stream-card { border-color: #d6dde5; background: #fff; box-shadow: none; }
.autonomous-process-details { margin-bottom: 10px; border: 1px solid #e2e6eb; border-radius: 10px; background: #f7f8fa; }
.autonomous-process-details > summary { display: flex; justify-content: space-between; gap: 16px; padding: 12px 14px; cursor: pointer; color: #4f5b66; }
.autonomous-process-details > summary span { color: #7a8490; font-size: 12px; }
.autonomous-progress-status { display: inline-flex; align-items: center; justify-content: flex-end; gap: 7px; text-align: right; }
.autonomous-progress-status i { width: 7px; height: 7px; flex: 0 0 7px; border-radius: 50%; background: #148675; }
.autonomous-progress-status[data-state="running"] { color: #217d6e; }
.autonomous-progress-status[data-state="running"] i { animation: autonomous-status-pulse 1.3s ease-in-out infinite; box-shadow: 0 0 0 0 rgba(20, 134, 117, .3); }
.autonomous-progress-status[data-state="waiting"] { color: #9b6a24; }
.autonomous-progress-status[data-state="waiting"] i { background: #d59436; }
.autonomous-progress-status[data-state="failed"] { color: #b44f47; }
.autonomous-progress-status[data-state="failed"] i { background: #cf5f56; }
.autonomous-progress-status[data-state="stopped"] i { background: #87938f; }
@keyframes autonomous-status-pulse { 50% { box-shadow: 0 0 0 5px rgba(20, 134, 117, 0); transform: scale(.86); } }
.autonomous-process-details > .autonomous-activity-list { padding: 0 12px 12px; }
.autonomous-activity-list { display: grid; gap: 10px; margin: 0; padding: 0; list-style: none; }
.autonomous-result-list { margin-top: 10px; }
.autonomous-activity-list > li { padding: 12px 14px; border: 1px solid #e2e6eb; border-radius: 10px; background: #f7f8fa; }
.autonomous-activity-list > li[data-kind="tool"] { border-color: #d3d8de; background: #eceff2; }
.autonomous-activity-list > li[data-kind="reply"] { border-color: transparent; background: #fff; padding-inline: 4px; }
.autonomous-activity-list > li[data-kind="question"] { border-color: #efd7a5; background: #fff9ed; }
.autonomous-activity-list > li[data-kind="conclusion"] { border-color: #9fc9bf; background: #eff9f6; box-shadow: inset 3px 0 #16836f; }
.autonomous-activity-list > li[data-kind="stop"], .autonomous-activity-list > li[data-status="failed"] { border-color: #efb9b4; background: #fff4f3; }
.autonomous-analysis { margin: 0; }
.autonomous-analysis > summary { display: grid; grid-template-columns: auto 1fr auto; align-items: center; gap: 10px; cursor: pointer; list-style: none; }
.autonomous-analysis > summary::-webkit-details-marker { display: none; }
.autonomous-analysis > summary > span:nth-child(2) { display: grid; gap: 3px; }
.autonomous-analysis > summary small { color: #7a8490; font-weight: 400; }
.autonomous-analysis > summary em, .autonomous-item-head em { color: #65717d; font-size: 12px; font-style: normal; }
.autonomous-analysis-grid { display: grid; gap: 9px; margin: 12px 0 0; padding-top: 12px; border-top: 1px solid #e1e5e9; }
.autonomous-analysis-grid div { display: grid; grid-template-columns: 135px 1fr; gap: 12px; }
.autonomous-analysis-grid dt { color: #5f6974; font-weight: 700; }
.autonomous-analysis-grid dd { margin: 0; color: #27323d; white-space: pre-wrap; }
.autonomous-item-head { display: grid; grid-template-columns: auto 1fr auto; align-items: center; gap: 10px; }
.autonomous-seq { display: inline-grid; place-items: center; min-width: 24px; height: 24px; border-radius: 7px; background: #dfe4e8; color: #53606c; font-size: 12px; font-weight: 700; }
.autonomous-tool-details { margin-top: 9px; }
.autonomous-tool-details summary { cursor: pointer; color: #44515e; font-weight: 700; }
.autonomous-tool-details section { margin-top: 9px; }
.autonomous-tool-details pre { max-height: 300px; overflow: auto; white-space: pre-wrap; }
.autonomous-answer { margin-bottom: 0; color: #1f2d2a; white-space: pre-wrap; }
.autonomous-direct-reply { margin: 0; }
.autonomous-inline-answer { display: grid; gap: 8px; margin-top: 12px; }
.autonomous-inline-answer textarea { width: 100%; box-sizing: border-box; }
@media (max-width: 760px) { .autonomous-analysis-grid div { grid-template-columns: 1fr; gap: 2px; } }
@media (prefers-reduced-motion: reduce) { .autonomous-progress-status[data-state="running"] i { animation: none; } }
</style>
