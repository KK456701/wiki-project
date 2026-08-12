<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'

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
const streamedNarratives = reactive<Record<string, string>>({})
const narrativeTargets = reactive<Record<string, string>>({})
let clock = 0
let typingClock = 0

const projectedTurns = computed(() => props.turns.map((turn, index) => ({
  key: String(turn.turnId || turn.clientMessageId || index),
  turn,
  activities: projectAutonomousActivities(turn, props.events),
})))

const pendingQuestionId = computed(() => latestPendingQuestionId(props.turns, props.events, props.runStatus))

clock = window.setInterval(() => { now.value = Date.now() }, 500)
typingClock = window.setInterval(() => {
  for (const [id, target] of Object.entries(narrativeTargets)) {
    const current = streamedNarratives[id] || ''
    if (current === target) continue
    if (!target.startsWith(current)) {
      streamedNarratives[id] = ''
      continue
    }
    const currentPoints = Array.from(current)
    const targetPoints = Array.from(target)
    streamedNarratives[id] = targetPoints.slice(0, currentPoints.length + 1).join('')
  }
}, 18)
onUnmounted(() => {
  window.clearInterval(clock)
  window.clearInterval(typingClock)
})

watch(projectedTurns, (turns) => {
  const activeIds = new Set<string>()
  for (const turn of turns) {
    for (const item of turn.activities) {
      if (item.kind !== 'MODEL') continue
      const target = item.analysisProcess || (item.status === 'RUNNING' ? '' : item.summary)
      activeIds.add(item.id)
      narrativeTargets[item.id] = target
      if (!(item.id in streamedNarratives)) streamedNarratives[item.id] = ''
    }
  }
  for (const id of Object.keys(narrativeTargets)) {
    if (!activeIds.has(id)) {
      delete narrativeTargets[id]
      delete streamedNarratives[id]
    }
  }
}, { immediate: true, deep: true })

function processActivities(items: AutonomousActivityItem[]): AutonomousActivityItem[] {
  return items.filter((item) => ['MODEL', 'TOOL', 'PROCESS'].includes(item.kind))
}

function visibleActivities(items: AutonomousActivityItem[]): AutonomousActivityItem[] {
  return items.filter((item) => !['MODEL', 'TOOL', 'PROCESS'].includes(item.kind))
}

function processBreakdown(items: AutonomousActivityItem[]): string {
  const models = items.filter((item) => item.kind === 'MODEL').length
  const tools = items.filter((item) => item.kind === 'TOOL').length
  const values: string[] = []
  if (models) values.push(`思考 ${models} 次`)
  if (tools) values.push(`调用 ${tools} 个工具`)
  return values.join(' · ')
}

function turnProgress(
  turn: Record<string, unknown>,
  items: AutonomousActivityItem[],
): { label: string, state: 'running' | 'waiting' | 'completed' | 'failed' | 'stopped' } {
  const turnStatus = String(turn.status || '').toUpperCase()
  const running = [...items].reverse().find((item) => item.status === 'RUNNING')
  if (running?.kind === 'TOOL') {
    return { label: `正在${running.toolDisplayName || running.tool || '核查数据'}`, state: 'running' }
  }
  if (running?.kind === 'MODEL') {
    return { label: running.analysisProcess ? '模型正在实时思考' : '模型正在开始思考', state: 'running' }
  }
  if (running) return { label: running.title || '正在处理', state: 'running' }

  if (turnStatus === 'RUNNING' || turnStatus === 'SENDING' || turnStatus === 'QUEUED') {
    const latest = items.at(-1)
    if (latest?.kind === 'TOOL') return { label: `已完成${latest.toolDisplayName || latest.tool || '数据核查'}，正在整理结果`, state: 'running' }
    if (latest?.kind === 'MODEL') return { label: '正在根据现有证据选择下一步', state: 'running' }
    return { label: '正在准备本轮排查', state: 'running' }
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

function statusText(status: string): string {
  return ({
    RUNNING: '执行中', SUCCEEDED: '已完成', FAILED: '失败', WAITING_USER: '等待回复',
    RETRYING: '正在重试', STOPPED: '已停止', CANCELLED: '已停止', READY: '可继续', COMPLETED: '已完成', QUEUED: '已排队',
  } as Record<string, string>)[status] || status
}

function pretty(value: unknown): string {
  try { return JSON.stringify(value, null, 2) } catch { return String(value ?? '') }
}

function analysisText(item: AutonomousActivityItem): string {
  return streamedNarratives[item.id] || (item.status === 'RUNNING'
    ? '正在等待模型返回第一个分析内容…'
    : item.analysisProcess || item.summary)
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
        <details
          v-if="processActivities(entry.activities).length"
          class="autonomous-process-details"
        >
          <summary>
            <span
              class="autonomous-status-medallion"
              :data-state="turnProgress(entry.turn, processActivities(entry.activities)).state"
              aria-hidden="true"
            >{{ turnProgress(entry.turn, processActivities(entry.activities)).state === 'completed' ? '✓' : '' }}</span>
            <span
              class="autonomous-progress-status"
              :data-state="turnProgress(entry.turn, processActivities(entry.activities)).state"
            >
              <strong>{{ turnProgress(entry.turn, processActivities(entry.activities)).label }}</strong>
            </span>
            <span class="autonomous-process-metric"><i aria-hidden="true">◷</i>{{ turnDuration(entry.turn) }}</span>
            <span v-if="processBreakdown(processActivities(entry.activities))" class="autonomous-process-metric"><i aria-hidden="true">♧</i>{{ processBreakdown(processActivities(entry.activities)) }}</span>
            <span class="autonomous-process-chevron" aria-hidden="true">⌄</span>
          </summary>
          <ol class="autonomous-activity-list">
          <li v-for="item in processActivities(entry.activities)" :key="item.id" :data-kind="item.kind.toLowerCase()" :data-status="item.status.toLowerCase()">
            <section v-if="item.kind === 'MODEL'" class="autonomous-analysis">
              <div class="autonomous-item-head">
                <span class="autonomous-event-dot" aria-hidden="true"></span>
                <strong>{{ item.status === 'RUNNING' ? '模型正在思考' : item.status === 'RETRYING' ? '思考未完成，正在重试' : '模型思考' }}</strong>
                <em>{{ statusText(item.status) }}</em>
              </div>
              <p
                class="autonomous-analysis-narrative"
                :class="{ 'is-streaming': item.status === 'RUNNING' }"
              >{{ analysisText(item) }}<i v-if="item.status === 'RUNNING'" class="autonomous-stream-caret" aria-hidden="true"></i></p>
              <p v-if="item.retryReason" class="autonomous-retry-reason">
                <strong>重试原因：</strong>{{ item.retryReason }}
              </p>
            </section>

            <template v-else-if="item.kind === 'TOOL'">
              <div class="autonomous-item-head">
                <span class="autonomous-event-dot" aria-hidden="true"></span>
                <strong>{{ item.status === 'RUNNING' ? '正在执行' : '已执行' }} {{ item.toolDisplayName || item.tool || '工具' }}</strong>
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
              <div class="autonomous-item-head"><span class="autonomous-event-dot" aria-hidden="true"></span><strong>{{ item.title }}</strong><em>{{ statusText(item.status) }}</em></div>
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
.autonomous-process-details { margin-bottom: 10px; border: 0; border-radius: 10px; background: #f7f8fa; }
.autonomous-process-details > summary { display: flex; align-items: center; justify-content: flex-start; gap: 10px; min-height: 42px; padding: 10px 13px; cursor: pointer; color: #4f5b66; list-style: none; }
.autonomous-process-details > summary::-webkit-details-marker { display: none; }
.autonomous-process-details > summary span { color: #7a8490; font-size: 12px; }
.autonomous-progress-status { min-width: 0; display: inline-flex; align-items: center; justify-content: flex-start; gap: 7px; text-align: left; }
.autonomous-progress-status strong { overflow: hidden; color: #45534f; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.autonomous-progress-status em { flex: 0 0 auto; color: #7a8783; font-size: 11px; font-style: normal; font-weight: 500; }
.autonomous-process-count { margin-left: 5px; color: #8a9490; font-size: 11px; }
.autonomous-progress-status i { width: 7px; height: 7px; flex: 0 0 7px; border-radius: 50%; background: #148675; }
.autonomous-progress-status[data-state="running"] { color: #217d6e; }
.autonomous-progress-status[data-state="running"] i { animation: autonomous-status-pulse 1.3s ease-in-out infinite; box-shadow: 0 0 0 0 rgba(20, 134, 117, .3); }
.autonomous-progress-status[data-state="waiting"] { color: #9b6a24; }
.autonomous-progress-status[data-state="waiting"] i { background: #d59436; }
.autonomous-progress-status[data-state="failed"] { color: #b44f47; }
.autonomous-progress-status[data-state="failed"] i { background: #cf5f56; }
.autonomous-progress-status[data-state="stopped"] i { background: #87938f; }
.autonomous-process-chevron { margin-left: auto; font-size: 14px !important; transition: transform .16s ease; }
.autonomous-process-details[open] .autonomous-process-chevron { transform: rotate(180deg); }
@keyframes autonomous-status-pulse { 50% { box-shadow: 0 0 0 5px rgba(20, 134, 117, 0); transform: scale(.86); } }
.autonomous-process-details > .autonomous-activity-list { position: relative; gap: 0; padding: 2px 13px 12px 32px; }
.autonomous-process-details > .autonomous-activity-list::before { content: ''; position: absolute; top: 6px; bottom: 18px; left: 20px; width: 1px; background: #d9dfdc; }
.autonomous-activity-list { display: grid; gap: 10px; margin: 0; padding: 0; list-style: none; }
.autonomous-result-list { margin-top: 10px; }
.autonomous-process-details .autonomous-activity-list > li { position: relative; padding: 9px 10px; border: 0; border-radius: 7px; background: transparent; }
.autonomous-process-details .autonomous-activity-list > li:hover { background: #f0f3f2; }
.autonomous-activity-list > li { padding: 12px 14px; border: 1px solid #e2e6eb; border-radius: 10px; background: #f7f8fa; }
.autonomous-activity-list > li[data-kind="tool"] { border-color: #d3d8de; background: #eceff2; }
.autonomous-process-details .autonomous-activity-list > li[data-kind="tool"] { background: transparent; }
.autonomous-activity-list > li[data-kind="reply"] { border-color: transparent; background: #fff; padding-inline: 4px; }
.autonomous-activity-list > li[data-kind="question"] { border-color: #efd7a5; background: #fff9ed; }
.autonomous-activity-list > li[data-kind="conclusion"] { border-color: #9fc9bf; background: #eff9f6; box-shadow: inset 3px 0 #16836f; }
.autonomous-activity-list > li[data-kind="stop"], .autonomous-activity-list > li[data-status="failed"] { border-color: #efb9b4; background: #fff4f3; }
.autonomous-analysis { margin: 0; }
.autonomous-analysis em, .autonomous-item-head em { color: #65717d; font-size: 12px; font-style: normal; }
.autonomous-analysis-narrative, .autonomous-thinking-placeholder { margin: 9px 0 0 22px; color: #33423e; line-height: 1.72; white-space: pre-wrap; }
.autonomous-thinking-placeholder { color: #72807c; }
.autonomous-analysis-narrative.is-streaming { color: #263d37; }
.autonomous-retry-reason { margin: 8px 0 0 22px; padding: 8px 10px; border-left: 3px solid #d49436; border-radius: 4px; background: #fff8eb; color: #7a5725; line-height: 1.6; }
.autonomous-stream-caret { display: inline-block; width: 2px; height: 1.1em; margin-left: 2px; vertical-align: -.18em; background: #148675; animation: autonomous-caret-blink .8s steps(1) infinite; }
@keyframes autonomous-caret-blink { 50% { opacity: 0; } }
.autonomous-analysis-grid { display: grid; gap: 9px; margin: 12px 0 0; padding-top: 12px; border-top: 1px solid #e1e5e9; }
.autonomous-analysis-grid div { display: grid; grid-template-columns: 135px 1fr; gap: 12px; }
.autonomous-analysis-grid dt { color: #5f6974; font-weight: 700; }
.autonomous-analysis-grid dd { margin: 0; color: #27323d; white-space: pre-wrap; }
.autonomous-item-head { display: grid; grid-template-columns: auto 1fr auto; align-items: center; gap: 10px; }
.autonomous-event-dot { position: relative; z-index: 1; width: 8px; height: 8px; border-radius: 50%; background: #16836f; box-shadow: 0 0 0 4px #f7f8fa; }
.autonomous-activity-list > li[data-kind="tool"] .autonomous-event-dot { background: #60717c; box-shadow: 0 0 0 4px rgba(96, 113, 124, .1); }
.autonomous-activity-list > li[data-status="retrying"] .autonomous-event-dot { background: #ca8a2e; box-shadow: 0 0 0 4px rgba(202, 138, 46, .12); }
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
@media (prefers-reduced-motion: reduce) { .autonomous-progress-status[data-state="running"] i, .autonomous-stream-caret { animation: none; } }

/* Compact activity shell: the status is visible, evidence and model reasoning stay folded. */
.autonomous-stream-card {
  padding: 16px;
  border: 1px solid #c9ded8;
  border-radius: 16px;
  background: linear-gradient(145deg, #fff, #fbfefd);
  box-shadow: 0 10px 28px rgba(19, 82, 68, .07);
}
.autonomous-process-details {
  overflow: hidden;
  margin: 0 0 12px;
  border: 1px solid #d4e3df;
  border-radius: 13px;
  background: #fff;
}
.autonomous-process-details > summary {
  min-height: 54px;
  gap: 12px;
  padding: 10px 15px;
  background: linear-gradient(90deg, #f6fbf9, #fff);
}
.autonomous-status-medallion {
  width: 30px;
  height: 30px;
  flex: 0 0 30px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  color: #fff;
  background: #168570;
  font-size: 15px;
  font-weight: 800;
}
.autonomous-status-medallion[data-state="running"] {
  border: 3px solid #d8eee8;
  background: transparent;
  box-shadow: inset 0 0 0 3px #2a9a83;
  animation: autonomous-medallion-pulse 1.15s ease-in-out infinite;
}
.autonomous-status-medallion[data-state="waiting"] { background: #d0963d; }
.autonomous-status-medallion[data-state="failed"] { background: #c75b51; }
.autonomous-status-medallion[data-state="stopped"] { background: #84918e; }
@keyframes autonomous-medallion-pulse { 50% { transform: scale(.9); opacity: .65; } }
.autonomous-progress-status { flex: 0 1 auto; }
.autonomous-progress-status strong { color: #17443a; font-size: 14px; }
.autonomous-process-metric {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding-left: 12px;
  border-left: 1px solid #d8e2df;
  color: #74837f;
  font-size: 11px;
  white-space: nowrap;
}
.autonomous-process-metric i { color: #64847c; font-size: 13px; font-style: normal; }
.autonomous-process-chevron { color: #16816e !important; font-size: 17px !important; }
.autonomous-process-details > .autonomous-activity-list {
  margin: 12px;
  padding: 0;
  border: 1px solid #dbe6e3;
  border-left: 3px solid #25a187;
  border-radius: 11px;
  background: #fbfdfc;
}
.autonomous-process-details > .autonomous-activity-list::before { display: none; }
.autonomous-process-details .autonomous-activity-list > li {
  padding: 13px 15px;
  border-bottom: 1px solid #e4ece9;
  border-radius: 0;
}
.autonomous-process-details .autonomous-activity-list > li:last-child { border-bottom: 0; }
.autonomous-process-details .autonomous-activity-list > li:hover { background: #f5faf8; }
.autonomous-analysis-narrative,
.autonomous-thinking-placeholder { margin: 12px 0 0 18px; color: #263e38; font-size: 12px; line-height: 1.85; }
.autonomous-result-list { margin-top: 0; }
.autonomous-activity-list > li[data-kind="reply"] {
  padding: 15px 17px;
  border: 1px solid #dce7e4;
  border-radius: 12px;
  background: #fff;
  box-shadow: 0 4px 14px rgba(21, 70, 59, .035);
}
.autonomous-direct-reply { color: #203b34; font-size: 13px; line-height: 1.8; }

@media (max-width: 680px) {
  .autonomous-process-metric { display: none; }
  .autonomous-stream-card { padding: 11px; }
  .autonomous-process-details > summary { padding-inline: 11px; }
}
@media (prefers-reduced-motion: reduce) { .autonomous-status-medallion[data-state="running"] { animation: none; } }
</style>
