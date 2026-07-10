"use strict";

var monitoringState = {
  tab: "plans",
  plans: [],
  selectedPlanId: "",
  results: [],
  alerts: [],
  latestResult: null,
  latestTraceByResultId: {},
};

var monitoringModal = document.getElementById("monitoringModal");
var monitoringMeta = document.getElementById("monitoringMeta");
var monitoringNotice = document.getElementById("monitoringNotice");
var monitoringPlanList = document.getElementById("monitoringPlanList");
var monitoringPlanDetail = document.getElementById("monitoringPlanDetail");
var monitoringPlanSearch = document.getElementById("monitoringPlanSearch");
var newMonitoringPlanButton = document.getElementById("newMonitoringPlanButton");
var monitoringPlanForm = document.getElementById("monitoringPlanForm");
var monitoringPlanFormTitle = document.getElementById("monitoringPlanFormTitle");
var monitoringPlanFormError = document.getElementById("monitoringPlanFormError");
var monitoringPlanId = document.getElementById("monitoringPlanId");
var monitoringRuleId = document.getElementById("monitoringRuleId");
var monitoringPlanName = document.getElementById("monitoringPlanName");
var monitoringFrequency = document.getElementById("monitoringFrequency");
var monitoringRunTime = document.getElementById("monitoringRunTime");
var monitoringDayField = document.getElementById("monitoringDayField");
var monitoringDayOfMonth = document.getElementById("monitoringDayOfMonth");
var monitoringMomEnabled = document.getElementById("monitoringMomEnabled");
var monitoringMomThreshold = document.getElementById("monitoringMomThreshold");
var monitoringYoyEnabled = document.getElementById("monitoringYoyEnabled");
var monitoringYoyThreshold = document.getElementById("monitoringYoyThreshold");
var saveMonitoringPlanButton = document.getElementById("saveMonitoringPlanButton");
var cancelMonitoringPlanButton = document.getElementById("cancelMonitoringPlanButton");
var closeMonitoringPlanFormButton = document.getElementById("closeMonitoringPlanFormButton");

function currentMonitoringHospitalId() {
  return (hospitalIdInput.value || "hospital_001").trim() || "hospital_001";
}

function monitoringEscape(value) {
  return String(value == null ? "" : value)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#039;");
}

function monitoringErrorText(detail, fallback) {
  if (typeof detail === "string") return detail;
  if (detail && Array.isArray(detail)) {
    return detail.map(function(item) { return item.msg || "输入内容不正确"; }).join("；");
  }
  return fallback || "监控请求失败";
}

async function monitoringRequest(path, options) {
  var config = options || {};
  config.headers = Object.assign({}, config.headers || {}, {
    "Authorization": "Bearer " + adminToken,
  });
  var response;
  try {
    response = await fetch(path, config);
  } catch (error) {
    throw new Error("无法连接后端服务，请先打开系统自检确认服务状态。");
  }
  var data = await response.json().catch(function() { return {}; });
  if (response.status === 401 || response.status === 403) {
    adminToken = "";
    sessionStorage.removeItem("adminToken");
    updateAdminUI();
    monitoringModal.hidden = true;
    requireAdminThenOpen("monitoring");
    throw new Error("管理员登录已失效，请重新登录。");
  }
  if (!response.ok) {
    throw new Error(monitoringErrorText(data.detail, "监控请求失败"));
  }
  return data;
}

function monitoringFrequencyText(plan) {
  if (plan.frequency === "daily") return "每日 " + (plan.run_time || "02:00");
  return "每月 " + (plan.day_of_month || 1) + " 日 " + (plan.run_time || "02:00");
}

function monitoringPlanStatusText(status) {
  return status === "enabled" ? "已启用" : "已停用";
}

function showMonitoringNotice(message, kind) {
  if (!message) {
    monitoringNotice.hidden = true;
    monitoringNotice.innerHTML = "";
    return;
  }
  monitoringNotice.hidden = false;
  monitoringNotice.classList.toggle("failed", kind === "failed");
  monitoringNotice.innerHTML = "";
  var text = document.createElement("span");
  text.textContent = message;
  monitoringNotice.appendChild(text);
}

async function loadMonitoringHealth() {
  try {
    var response = await fetch("/api/health/summary");
    var data = await response.json();
    if (!response.ok) throw new Error(data.detail || "系统自检读取失败");
    var scheduler = (data.items || []).find(function(item) {
      return item.key === "monitoring_scheduler";
    });
    if (!scheduler) return;
    monitoringMeta.textContent = currentMonitoringHospitalId() + " · 指标调度器" + scheduler.status_text + " · " + Number(scheduler.enabled_plan_count || 0) + " 个启用计划";
    if (scheduler.status !== "ok") {
      showMonitoringNotice("指标调度器异常，定时计划可能不会执行。请先查看系统自检。", "failed");
      var button = document.createElement("button");
      button.type = "button";
      button.className = "ghost";
      button.textContent = "打开系统自检";
      button.addEventListener("click", function() {
        selfCheckModal.hidden = false;
        loadSelfCheck();
      });
      monitoringNotice.appendChild(button);
    }
  } catch (error) {
    monitoringMeta.textContent = currentMonitoringHospitalId() + " · 调度状态读取失败";
    showMonitoringNotice(error.message, "failed");
  }
}

function selectedMonitoringPlan() {
  return monitoringState.plans.find(function(plan) {
    return plan.plan_id === monitoringState.selectedPlanId;
  }) || null;
}

function renderMonitoringPlanList() {
  monitoringPlanList.innerHTML = "";
  var query = (monitoringPlanSearch.value || "").trim().toLowerCase();
  var plans = monitoringState.plans.filter(function(plan) {
    return !query || String(plan.plan_name || "").toLowerCase().includes(query) || String(plan.rule_id || "").toLowerCase().includes(query);
  });
  if (!plans.length) {
    var empty = document.createElement("div");
    empty.className = "monitoring-empty";
    empty.textContent = query ? "没有匹配的运行计划。" : "当前医院还没有运行计划。";
    monitoringPlanList.appendChild(empty);
    return;
  }
  plans.forEach(function(plan) {
    var button = document.createElement("button");
    button.type = "button";
    button.className = "monitoring-plan-item" + (plan.plan_id === monitoringState.selectedPlanId ? " active" : "");
    var name = document.createElement("strong");
    name.textContent = plan.plan_name || plan.rule_id;
    var code = document.createElement("span");
    code.className = "monitoring-kicker";
    code.textContent = plan.rule_id + " · " + monitoringFrequencyText(plan);
    var status = document.createElement("span");
    status.className = "monitoring-status " + (plan.status || "");
    status.textContent = monitoringPlanStatusText(plan.status);
    button.append(name, code, status);
    button.addEventListener("click", function() {
      monitoringState.selectedPlanId = plan.plan_id;
      renderMonitoringPlanList();
      renderMonitoringPlanDetail();
    });
    monitoringPlanList.appendChild(button);
  });
}

function createMonitoringSummaryCell(label, value) {
  var cell = document.createElement("div");
  cell.className = "monitoring-summary-cell";
  var name = document.createElement("span");
  name.textContent = label;
  var strong = document.createElement("strong");
  strong.textContent = value;
  cell.append(name, strong);
  return cell;
}

function renderLatestMonitoringResult(container, plan) {
  var result = monitoringState.latestResult;
  if (!result || result.plan_id !== plan.plan_id) return;
  var block = document.createElement("div");
  block.className = "monitoring-record";
  var title = document.createElement("strong");
  title.textContent = result.run_status === "failed" ? "本次运行失败" : "本次运行完成";
  var grid = document.createElement("div");
  grid.className = "monitoring-record-grid";
  grid.append(
    createMonitoringSummaryCell("指标结果", result.result_value == null ? "无" : String(result.result_value)),
    createMonitoringSummaryCell("环比", result.mom_change_rate == null ? "暂无基线" : String(result.mom_change_rate) + "%"),
    createMonitoringSummaryCell("同比", result.yoy_change_rate == null ? "暂无基线" : String(result.yoy_change_rate) + "%"),
    createMonitoringSummaryCell("耗时", formatTraceDuration(result.duration_ms))
  );
  var actions = document.createElement("div");
  actions.className = "monitoring-item-actions";
  if (result.trace_id) {
    var traceButton = document.createElement("button");
    traceButton.type = "button";
    traceButton.className = "ghost";
    traceButton.textContent = "查看执行链路";
    traceButton.addEventListener("click", function() { showTrace(result.trace_id); });
    actions.appendChild(traceButton);
  }
  if (result.run_status === "failed") {
    var recovery = document.createElement("button");
    recovery.type = "button";
    recovery.textContent = "前往恢复中心";
    recovery.addEventListener("click", function() { requireAdminThenOpen("recovery"); });
    actions.appendChild(recovery);
  }
  block.append(title, grid, actions);
  container.appendChild(block);
}

function renderMonitoringPlanDetail() {
  var plan = selectedMonitoringPlan();
  monitoringPlanDetail.innerHTML = "";
  if (!plan) {
    var empty = document.createElement("div");
    empty.className = "monitoring-empty";
    empty.textContent = "选择一个运行计划，或创建第一个计划。";
    monitoringPlanDetail.appendChild(empty);
    return;
  }
  var head = document.createElement("div");
  head.className = "monitoring-detail-head";
  var heading = document.createElement("div");
  var title = document.createElement("h3");
  title.textContent = plan.plan_name;
  var code = document.createElement("div");
  code.className = "review-meta";
  code.textContent = plan.rule_id + " · " + monitoringPlanStatusText(plan.status);
  heading.append(title, code);
  var actions = document.createElement("div");
  actions.className = "monitoring-item-actions";
  var edit = document.createElement("button");
  edit.type = "button";
  edit.className = "ghost";
  edit.textContent = "编辑";
  edit.addEventListener("click", function() { openMonitoringPlanForm(plan); });
  var toggle = document.createElement("button");
  toggle.type = "button";
  toggle.className = "ghost";
  toggle.textContent = plan.status === "enabled" ? "停用" : "启用";
  toggle.addEventListener("click", function() { setMonitoringPlanStatus(plan, toggle); });
  actions.append(edit, toggle);
  head.append(heading, actions);

  var summary = document.createElement("div");
  summary.className = "monitoring-summary-grid";
  summary.append(
    createMonitoringSummaryCell("运行频率", plan.frequency === "daily" ? "每日" : "每月"),
    createMonitoringSummaryCell("运行时间", monitoringFrequencyText(plan)),
    createMonitoringSummaryCell("环比阈值", plan.mom_enabled ? plan.mom_threshold_pct + "%" : "未启用"),
    createMonitoringSummaryCell("同比阈值", plan.yoy_enabled ? plan.yoy_threshold_pct + "%" : "未启用")
  );

  var run = document.createElement("div");
  run.className = "monitoring-run-box";
  var runTitle = document.createElement("strong");
  runTitle.textContent = "立即运行";
  var runHint = document.createElement("p");
  runHint.textContent = "统计范围留空时，系统自动计算最近一个完整周期。也可以填写 2026-07-01~2026-07-31。";
  var runActions = document.createElement("div");
  runActions.className = "monitoring-item-actions";
  var period = document.createElement("input");
  period.id = "monitoringRunPeriod";
  period.className = "monitoring-input";
  period.placeholder = "可选统计范围";
  var runButton = document.createElement("button");
  runButton.type = "button";
  runButton.textContent = "立即运行";
  runButton.addEventListener("click", function() {
    runMonitoringPlan(plan, period.value.trim(), runButton);
  });
  runActions.append(period, runButton);
  run.append(runTitle, runHint, runActions);
  monitoringPlanDetail.append(head, summary, run);
  renderLatestMonitoringResult(monitoringPlanDetail, plan);
}

async function loadMonitoringPlans(preferredPlanId) {
  monitoringPlanList.innerHTML = '<div class="monitoring-loading">正在读取运行计划...</div>';
  try {
    var data = await monitoringRequest(
      "/api/monitoring/plans?hospital_id=" + encodeURIComponent(currentMonitoringHospitalId())
    );
    monitoringState.plans = data.items || [];
    if (preferredPlanId && monitoringState.plans.some(function(plan) { return plan.plan_id === preferredPlanId; })) {
      monitoringState.selectedPlanId = preferredPlanId;
    } else if (!monitoringState.plans.some(function(plan) { return plan.plan_id === monitoringState.selectedPlanId; })) {
      monitoringState.selectedPlanId = monitoringState.plans.length ? monitoringState.plans[0].plan_id : "";
    }
    renderMonitoringPlanList();
    renderMonitoringPlanDetail();
    loadMonitoringHealth();
  } catch (error) {
    monitoringPlanList.innerHTML = "";
    showMonitoringNotice(error.message, "failed");
  }
}

function resetMonitoringPlanForm() {
  monitoringPlanForm.reset();
  monitoringPlanId.value = "";
  monitoringRunTime.value = "02:00";
  monitoringDayOfMonth.value = "1";
  monitoringMomEnabled.checked = true;
  monitoringMomThreshold.value = "20";
  monitoringYoyEnabled.checked = true;
  monitoringYoyThreshold.value = "30";
  monitoringPlanFormError.hidden = true;
  monitoringPlanFormError.textContent = "";
  updateMonitoringDayField();
}

function openMonitoringPlanForm(plan) {
  resetMonitoringPlanForm();
  monitoringPlanFormTitle.textContent = plan ? "编辑运行计划" : "新建运行计划";
  if (plan) {
    monitoringPlanId.value = plan.plan_id;
    monitoringRuleId.value = plan.rule_id;
    monitoringRuleId.readOnly = true;
    monitoringPlanName.value = plan.plan_name;
    monitoringFrequency.value = plan.frequency;
    monitoringRunTime.value = plan.run_time;
    monitoringDayOfMonth.value = plan.day_of_month || 1;
    monitoringMomEnabled.checked = !!plan.mom_enabled;
    monitoringMomThreshold.value = plan.mom_threshold_pct;
    monitoringYoyEnabled.checked = !!plan.yoy_enabled;
    monitoringYoyThreshold.value = plan.yoy_threshold_pct;
  } else {
    monitoringRuleId.readOnly = false;
  }
  updateMonitoringDayField();
  monitoringPlanForm.hidden = false;
  monitoringRuleId.focus();
}

function closeMonitoringPlanForm() {
  monitoringPlanForm.hidden = true;
  monitoringPlanFormError.hidden = true;
}

function updateMonitoringDayField() {
  monitoringDayField.hidden = monitoringFrequency.value !== "monthly";
}

function monitoringPlanPayload() {
  return {
    hospital_id: currentMonitoringHospitalId(),
    rule_id: monitoringRuleId.value.trim(),
    plan_name: monitoringPlanName.value.trim(),
    frequency: monitoringFrequency.value,
    run_time: monitoringRunTime.value,
    day_of_month: Number(monitoringDayOfMonth.value || 1),
    mom_enabled: monitoringMomEnabled.checked,
    mom_threshold_pct: Number(monitoringMomThreshold.value),
    yoy_enabled: monitoringYoyEnabled.checked,
    yoy_threshold_pct: Number(monitoringYoyThreshold.value),
    created_by: currentUser ? currentUser.accountId : "admin",
  };
}

async function saveMonitoringPlan(event) {
  event.preventDefault();
  if (!monitoringPlanForm.reportValidity()) return;
  var planId = monitoringPlanId.value;
  var payload = monitoringPlanPayload();
  var path = "/api/monitoring/plans" + (planId ? "/" + encodeURIComponent(planId) : "");
  saveMonitoringPlanButton.disabled = true;
  saveMonitoringPlanButton.textContent = "保存中...";
  monitoringPlanFormError.hidden = true;
  try {
    var data = await monitoringRequest(path, {
      method: planId ? "PUT" : "POST",
      headers: {"Content-Type": "application/json"},
      body: JSON.stringify(payload),
    });
    closeMonitoringPlanForm();
    monitoringState.selectedPlanId = data.plan_id;
    await loadMonitoringPlans(data.plan_id);
  } catch (error) {
    monitoringPlanFormError.textContent = error.message;
    monitoringPlanFormError.hidden = false;
  } finally {
    saveMonitoringPlanButton.disabled = false;
    saveMonitoringPlanButton.textContent = "保存计划";
  }
}

async function setMonitoringPlanStatus(plan, button) {
  button.disabled = true;
  var action = plan.status === "enabled" ? "disable" : "enable";
  try {
    await monitoringRequest(
      "/api/monitoring/plans/" + encodeURIComponent(plan.plan_id) + "/" + action + "?hospital_id=" + encodeURIComponent(currentMonitoringHospitalId()),
      {method: "POST"}
    );
    await loadMonitoringPlans(plan.plan_id);
  } catch (error) {
    showMonitoringNotice(error.message, "failed");
  } finally {
    button.disabled = false;
  }
}

async function runMonitoringPlan(plan, period, button) {
  button.disabled = true;
  button.textContent = "运行中...";
  showMonitoringNotice("正在通过 DBHub 计算指标，请稍候。", "");
  try {
    var data = await monitoringRequest(
      "/api/monitoring/plans/" + encodeURIComponent(plan.plan_id) + "/run",
      {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({
          hospital_id: currentMonitoringHospitalId(),
          stat_period: period || null,
        }),
      }
    );
    monitoringState.latestResult = data;
    if (data.trace_id) monitoringState.latestTraceByResultId[String(data.id)] = data.trace_id;
    showMonitoringNotice(data.run_status === "failed" ? "运行失败，可前往恢复中心重试。" : "指标运行完成，结果和链路已生成。", data.run_status === "failed" ? "failed" : "");
    renderMonitoringPlanDetail();
  } catch (error) {
    showMonitoringNotice(error.message, "failed");
  } finally {
    button.disabled = false;
    button.textContent = "立即运行";
  }
}

function openMonitoringWorkbench() {
  monitoringModal.hidden = false;
  monitoringState.tab = "plans";
  showMonitoringNotice("", "");
  loadMonitoringHealth();
  loadMonitoringPlans();
}

window.openMonitoringWorkbench = openMonitoringWorkbench;

newMonitoringPlanButton.addEventListener("click", function() { openMonitoringPlanForm(null); });
monitoringPlanSearch.addEventListener("input", renderMonitoringPlanList);
monitoringFrequency.addEventListener("change", updateMonitoringDayField);
monitoringPlanForm.addEventListener("submit", saveMonitoringPlan);
cancelMonitoringPlanButton.addEventListener("click", closeMonitoringPlanForm);
closeMonitoringPlanFormButton.addEventListener("click", closeMonitoringPlanForm);
hospitalIdInput.addEventListener("change", function() {
  if (!monitoringModal.hidden) {
    monitoringState.selectedPlanId = "";
    monitoringState.latestResult = null;
    loadMonitoringPlans();
  }
});
