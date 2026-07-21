<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import {
  diagnoseMonitoringAlert, loadMonitoringAlerts, loadMonitoringPlans, loadMonitoringResults,
  loadMonitoringSchedulerStatus, loginAdmin, logoutAdmin, runMonitoringPlan,
  saveMonitoringPlan, setMonitoringPlanStatus, transitionMonitoringAlert,
  type MonitoringAlert, type MonitoringPlan, type MonitoringResult, type MonitoringSchedulerStatus,
} from '../api/agent'
import { useAgentStore } from '../stores/agent'

const store = useAgentStore()
const router = useRouter()
const adminToken = ref(sessionStorage.getItem('vueAdminToken') || '')
const password = ref('')
const loading = ref(false)
const error = ref('')
const message = ref('')
const tab = ref<'plans' | 'results' | 'alerts'>('plans')
const plans = ref<MonitoringPlan[]>([])
const results = ref<MonitoringResult[]>([])
const alerts = ref<MonitoringAlert[]>([])
const scheduler = ref<MonitoringSchedulerStatus | null>(null)
const runPeriod = ref('')
const planForm = ref({ rule_id: '', plan_name: '', frequency: 'monthly', run_time: '02:00', day_of_month: 1,
  mom_enabled: true, mom_threshold_pct: 20, yoy_enabled: true, yoy_threshold_pct: 30 })
const openAlerts = computed(() => alerts.value.filter((item) => item.status === 'open').length)

onMounted(async () => {
  if (!store.isAuthenticated || !store.user) { await router.replace('/'); return }
  if (adminToken.value) await refresh()
})

async function login() {
  if (!password.value) return
  loading.value = true; error.value = ''
  try {
    const value = await loginAdmin(password.value)
    adminToken.value = value.token; sessionStorage.setItem('vueAdminToken', value.token); password.value = ''
    await refresh(); message.value = '监控维护模式已开启。'
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '管理员登录失败。' }
  finally { loading.value = false }
}

async function logout() {
  await logoutAdmin(adminToken.value).catch(() => undefined)
  adminToken.value = ''; sessionStorage.removeItem('vueAdminToken'); plans.value = []; results.value = []; alerts.value = []
}

async function refresh() {
  if (!store.user || !adminToken.value) return
  loading.value = true; error.value = ''
  try {
    const [p, r, a, s] = await Promise.all([
      loadMonitoringPlans(adminToken.value, store.token, store.user.hospitalId),
      loadMonitoringResults(adminToken.value, store.token, store.user.hospitalId),
      loadMonitoringAlerts(adminToken.value, store.token, store.user.hospitalId),
      loadMonitoringSchedulerStatus(adminToken.value)
        .catch(() => ({ enabled: false, status: 'unavailable' })),
    ])
    plans.value = p.items; results.value = r.items; alerts.value = a.items; scheduler.value = s
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '监控数据加载失败。' }
  finally { loading.value = false }
}

async function createPlan() {
  if (!store.user || !planForm.value.rule_id.trim() || !planForm.value.plan_name.trim()) return
  await operate(async () => {
    await saveMonitoringPlan(adminToken.value, store.token, {
      ...planForm.value, hospital_id: store.user?.hospitalId, timezone: 'Asia/Shanghai',
      created_by: store.user?.userId,
    })
    planForm.value.rule_id = ''; planForm.value.plan_name = ''
  }, '监控计划已创建。')
}

async function toggle(plan: MonitoringPlan) {
  if (!store.user) return
  await operate(() => setMonitoringPlanStatus(adminToken.value, store.token, store.user!.hospitalId,
    plan.plan_id, plan.status !== 'enabled'), plan.status === 'enabled' ? '计划已停用。' : '计划已启用。')
}

async function runPlan(plan: MonitoringPlan) {
  if (!store.user) return
  await operate(() => runMonitoringPlan(adminToken.value, store.token, store.user!.hospitalId,
    plan.plan_id, runPeriod.value.trim()), '监控计划已执行，结果和预警已刷新。')
}

async function alertAction(alert: MonitoringAlert, action: 'acknowledge' | 'close') {
  if (!store.user) return
  await operate(() => transitionMonitoringAlert(adminToken.value, store.token, store.user!.hospitalId,
    store.user!.userId, alert.alert_id, action), action === 'acknowledge' ? '预警已确认。' : '预警已关闭。')
}

async function diagnose(alert: MonitoringAlert) {
  if (!store.user) return
  await operate(() => diagnoseMonitoringAlert(adminToken.value, store.token, store.user!.hospitalId,
    store.user!.userId, alert.alert_id), '预警诊断已重新执行。')
}

async function operate(action: () => Promise<unknown>, success: string) {
  loading.value = true; error.value = ''; message.value = ''
  try { await action(); await refresh(); message.value = success }
  catch (reason) { error.value = reason instanceof Error ? reason.message : '监控操作失败。' }
  finally { loading.value = false }
}

function fmt(value: unknown) { return value ? new Date(String(value)).toLocaleString('zh-CN', { hour12: false }) : '—' }
function pct(value: unknown) { return value === null || value === undefined ? '—' : `${Number(value).toFixed(2)}%` }
</script>

<template>
  <main class="monitor-shell">
    <header class="monitor-head">
      <div><p class="eyebrow">Indicator operations</p><h1>指标监控工作台</h1><p>在同一医院权限边界内维护计划，审阅历史结果并处置预警。</p></div>
      <nav><RouterLink class="quiet-button" to="/">返回对话</RouterLink><RouterLink class="quiet-button" to="/runs">运行观察</RouterLink><RouterLink class="quiet-button" to="/terminology">医学术语</RouterLink><button v-if="adminToken" class="quiet-button" @click="refresh">刷新</button></nav>
    </header>

    <section v-if="!adminToken" class="monitor-login">
      <div><p class="eyebrow">Administrative boundary</p><h2>进入监控维护模式</h2><p>监控数据同时要求管理员会话和当前医院登录，不能跨医院查看。</p></div>
      <form @submit.prevent="login"><input v-model="password" type="password" autocomplete="current-password" placeholder="管理员密码" /><button class="primary-button" :disabled="loading">进入工作台</button></form>
    </section>

    <template v-else>
      <section class="monitor-context"><strong>{{ store.user?.hospitalId }}</strong><span>{{ plans.length }} 个计划</span><span>{{ results.length }} 条结果</span><span>{{ openAlerts }} 条待处理预警</span><span>Java 调度：{{ scheduler?.status || 'unknown' }}</span><button @click="logout">退出维护模式</button></section>
      <p class="monitor-boundary">Java 已支持手工运行、租约幂等、波动检测和预警诊断；自动扫描默认关闭，切换权威运行时后通过 MONITORING_SCHEDULER_ENABLED=true 开启。</p>
      <p v-if="message" class="monitor-message">{{ message }}</p><p v-if="error" class="runs-error">{{ error }}</p>
      <nav class="monitor-tabs"><button :class="{ active: tab === 'plans' }" @click="tab = 'plans'">运行计划</button><button :class="{ active: tab === 'results' }" @click="tab = 'results'">运行结果</button><button :class="{ active: tab === 'alerts' }" @click="tab = 'alerts'">预警处置</button></nav>

      <section v-if="tab === 'plans'" class="monitor-grid">
        <form class="monitor-plan-form" @submit.prevent="createPlan">
          <header><p class="eyebrow">New schedule</p><h2>新增监控计划</h2></header>
          <label>指标规则 ID<input v-model="planForm.rule_id" placeholder="MQSI2025_001" /></label><label>计划名称<input v-model="planForm.plan_name" placeholder="每月转科率监测" /></label>
          <div><label>频率<select v-model="planForm.frequency"><option value="daily">每日</option><option value="monthly">每月</option></select></label><label>运行时间<input v-model="planForm.run_time" type="time" /></label><label v-if="planForm.frequency === 'monthly'">每月日期<input v-model.number="planForm.day_of_month" type="number" min="1" max="28" /></label></div>
          <div><label>环比阈值（%）<input v-model.number="planForm.mom_threshold_pct" type="number" min="0.01" /></label><label>同比阈值（%）<input v-model.number="planForm.yoy_threshold_pct" type="number" min="0.01" /></label></div>
          <button class="primary-button" :disabled="loading">创建计划</button>
        </form>
        <article class="monitor-panel"><header><div><h2>现有计划</h2><span>下次执行时间由服务端计算</span></div><label>手工统计周期（可选）<input v-model="runPeriod" placeholder="2026-01-01~2026-01-31" /></label></header><div v-if="plans.length" class="monitor-cards"><article v-for="plan in plans" :key="plan.plan_id"><div><strong>{{ plan.plan_name }}</strong><code>{{ plan.rule_id }}</code></div><dl><div><dt>频率</dt><dd>{{ plan.frequency }} · {{ plan.run_time }}</dd></div><div><dt>下次执行</dt><dd>{{ fmt(plan.next_run_at) }}</dd></div><div><dt>阈值</dt><dd>环比 {{ plan.mom_threshold_pct }}% / 同比 {{ plan.yoy_threshold_pct }}%</dd></div></dl><p><button @click="runPlan(plan)">立即运行</button><button :data-enabled="plan.status === 'enabled'" @click="toggle(plan)">{{ plan.status === 'enabled' ? '停用' : '启用' }}</button></p></article></div><p v-else>当前医院还没有监控计划。</p></article>
      </section>

      <section v-if="tab === 'results'" class="monitor-panel monitor-wide"><header><h2>历史运行结果</h2><span>只读审阅</span></header><div class="monitor-table"><table><thead><tr><th>结果 ID</th><th>指标</th><th>统计期</th><th>结果</th><th>状态</th><th>触发方式</th><th>耗时</th><th>生成时间</th></tr></thead><tbody><tr v-for="item in results" :key="item.id"><td>{{ item.id }}</td><td><code>{{ item.rule_id }}</code></td><td>{{ item.stat_period }}</td><td>{{ pct(item.result_value) }}</td><td><span class="run-status" :data-status="item.run_status">{{ item.run_status || 'legacy' }}</span></td><td>{{ item.trigger_type || '—' }}</td><td>{{ item.duration_ms || 0 }}ms</td><td>{{ fmt(item.created_at) }}</td></tr></tbody></table><p v-if="!results.length">暂无运行结果。</p></div></section>

      <section v-if="tab === 'alerts'" class="monitor-panel monitor-wide"><header><h2>指标预警</h2><span>确认、诊断与关闭均记录在当前医院范围内</span></header><div class="monitor-cards alert-cards"><article v-for="alert in alerts" :key="alert.alert_id"><div><strong>{{ alert.conclusion_code }}</strong><code>{{ alert.rule_id }} · {{ alert.alert_id }}</code></div><dl><div><dt>当前值</dt><dd>{{ pct(alert.current_value) }}</dd></div><div><dt>波动</dt><dd>环比 {{ pct(alert.mom_change_rate) }} / 同比 {{ pct(alert.yoy_change_rate) }}</dd></div><div><dt>状态</dt><dd>{{ alert.status }} · {{ alert.diagnose_status }}</dd></div></dl><p><button @click="diagnose(alert)">重新诊断</button><button v-if="alert.status === 'open'" @click="alertAction(alert, 'acknowledge')">确认</button><button v-if="alert.status !== 'closed'" @click="alertAction(alert, 'close')">关闭</button></p></article><p v-if="!alerts.length">暂无指标预警。</p></div></section>
    </template>
  </main>
</template>
