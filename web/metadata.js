"use strict";

var metadataPage = document.getElementById("metadataPage");
var metadataDbSelect = document.getElementById("metadataDbSelect");
var metadataSyncButton = document.getElementById("metadataSyncButton");
var metadataRefreshConnectionButton = document.getElementById("metadataRefreshConnectionButton");
var metadataNotice = document.getElementById("metadataNotice");
var metadataHospitalValue = document.getElementById("metadataHospitalValue");
var metadataSyncedAtValue = document.getElementById("metadataSyncedAtValue");
var metadataTableCountValue = document.getElementById("metadataTableCountValue");
var metadataColumnCountValue = document.getElementById("metadataColumnCountValue");
var metadataChanges = document.getElementById("metadataChanges");
var metadataChangeCount = document.getElementById("metadataChangeCount");
var metadataAffectedRules = document.getElementById("metadataAffectedRules");
var metadataAffectedCount = document.getElementById("metadataAffectedCount");
var metadataConnectionSummary = document.getElementById("metadataConnectionSummary");
var metadataSourceList = document.getElementById("metadataSourceList");

var metadataState = {
  sources: [],
  overview: null,
  loadingSources: false,
  syncing: false,
};

var metadataChangeNames = {
  table_added: "新增数据表",
  table_deleted: "删除数据表",
  column_added: "新增字段",
  column_deleted: "删除字段",
  column_type_changed: "字段类型变化",
  column_nullable_changed: "字段可空性变化",
};

function currentMetadataHospitalId() {
  return (hospitalIdInput.value || "hospital_001").trim() || "hospital_001";
}

function selectedMetadataDatabase() {
  return metadataDbSelect.value || "win60_qa_991827";
}

function setMetadataNotice(message, state) {
  metadataNotice.textContent = message;
  metadataNotice.className = "metadata-notice" + (state ? " " + state : "");
}

async function readMetadataResponse(response) {
  var text = await response.text();
  if (!text) return {};
  try {
    return JSON.parse(text);
  } catch (_) {
    return {detail: text.trim() || "服务返回了无法识别的错误"};
  }
}

function metadataDateText(value) {
  if (!value) return "尚未同步";
  var date = new Date(value.replace(" ", "T"));
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString("zh-CN", {hour12: false});
}

function normalizeMetadataSource(source) {
  return {
    name: source.id || source.name || source.database || "未命名数据源",
    type: source.type || "mysql",
    host: source.host || "",
    port: source.port || "",
    database: source.database || source.id || source.name || "",
    tools: Array.isArray(source.tools) ? source.tools : [],
  };
}

function renderMetadataOverview(data) {
  metadataState.overview = data;
  metadataHospitalValue.textContent = data.hospital_id || currentMetadataHospitalId();
  metadataSyncedAtValue.textContent = metadataDateText(data.synced_at);
  metadataTableCountValue.textContent = String(data.table_count || 0);
  metadataColumnCountValue.textContent = String(data.column_count || 0);
  renderMetadataChanges(data.changes || [], !!data.has_snapshot);
  renderAffectedRules(data.affected_rules || [], !!data.has_snapshot);
  if (!data.has_snapshot) {
    setMetadataNotice("当前医院尚未保存数据库结构。点击“同步数据库结构”完成首次同步。", "");
  } else if (!(data.changes || []).length) {
    setMetadataNotice("数据库结构与上次一致，当前没有需要处理的变化。", "success");
  } else {
    setMetadataNotice("最近一次同步发现 " + data.changes.length + " 项结构变化，请查看下方影响范围。", "");
  }
}

function renderMetadataChanges(changes, hasSnapshot) {
  metadataChangeCount.textContent = String(changes.length);
  metadataChanges.innerHTML = "";
  if (!changes.length) {
    var empty = document.createElement("div");
    empty.className = "metadata-empty";
    empty.textContent = hasSnapshot
      ? "数据库结构与上次一致。"
      : "完成首次同步后，这里会展示新增、删除和类型变化。";
    metadataChanges.appendChild(empty);
    return;
  }
  changes.forEach(function(change) {
    var item = document.createElement("div");
    var deleted = /deleted/.test(change.change_type || "");
    var changed = /changed/.test(change.change_type || "");
    item.className = "metadata-change-item" + (deleted ? " deleted" : changed ? " changed" : "");
    var title = document.createElement("strong");
    title.textContent = metadataChangeNames[change.change_type] || "结构变化";
    var detail = document.createElement("span");
    var field = change.field_name ? "." + change.field_name : "";
    detail.textContent = (change.table_name || "未知数据表") + field + (change.change_desc ? " · " + change.change_desc : "");
    item.append(title, detail);
    metadataChanges.appendChild(item);
  });
}

function renderAffectedRules(rules, hasSnapshot) {
  metadataAffectedCount.textContent = String(rules.length);
  metadataAffectedRules.innerHTML = "";
  if (!rules.length) {
    var empty = document.createElement("div");
    empty.className = "metadata-empty";
    empty.textContent = hasSnapshot
      ? "本次结构变化未影响已配置指标。"
      : "同步后将根据本院字段映射自动检查受影响指标。";
    metadataAffectedRules.appendChild(empty);
    return;
  }
  rules.forEach(function(rule) {
    var item = document.createElement("div");
    item.className = "metadata-rule-item";
    var title = document.createElement("strong");
    title.textContent = rule.rule_id || "未命名指标";
    var detail = document.createElement("span");
    var businessFields = (rule.business_fields || []).join("、") || "未记录业务字段";
    var columns = (rule.matched_columns || []).join("、") || "未记录数据库字段";
    detail.textContent = "业务字段：" + businessFields + " · 数据库字段：" + columns;
    item.append(title, detail);
    metadataAffectedRules.appendChild(item);
  });
}

function renderMetadataSources(sources) {
  metadataSourceList.innerHTML = "";
  if (!sources.length) {
    var empty = document.createElement("div");
    empty.className = "metadata-empty";
    empty.textContent = "未发现可用数据源。请确认本地数据库服务和 DBHub sidecar 已启动。";
    metadataSourceList.appendChild(empty);
    return;
  }
  sources.forEach(function(source) {
    var systemSource = source.name === "wiki_agent_runtime";
    var item = document.createElement("div");
    item.className = "metadata-source-item";
    var head = document.createElement("div");
    head.className = "metadata-source-head";
    var name = document.createElement("strong");
    name.textContent = source.name;
    var role = document.createElement("span");
    role.textContent = systemSource ? "系统管理库" : "医院业务库";
    head.append(name, role);
    var meta = document.createElement("div");
    meta.className = "metadata-source-meta";
    meta.textContent = source.type + " · " + (source.host || "本机") + (source.port ? ":" + source.port : "") + " / " + source.database;
    var tools = document.createElement("div");
    tools.className = "metadata-tool-list";
    source.tools.forEach(function(tool) {
      var pill = document.createElement("span");
      pill.className = "metadata-tool-pill";
      pill.textContent = (tool.name || "未命名 MCP 工具") + (tool.readonly ? " · 只读" : "");
      tools.appendChild(pill);
    });
    item.append(head, meta, tools);
    metadataSourceList.appendChild(item);
  });
}

function updateMetadataBusinessSources(sources) {
  var previous = selectedMetadataDatabase();
  var businessSources = sources.filter(function(source) {
    return source.name !== "wiki_agent_runtime";
  });
  metadataDbSelect.innerHTML = "";
  businessSources.forEach(function(source) {
    var option = document.createElement("option");
    option.value = source.name;
    option.textContent = source.name;
    metadataDbSelect.appendChild(option);
  });
  var preferred = businessSources.find(function(source) { return source.name === previous; })
    || businessSources.find(function(source) { return source.name === "win60_qa_991827"; })
    || businessSources[0];
  if (preferred) metadataDbSelect.value = preferred.name;
  metadataSyncButton.disabled = !preferred;
}

async function loadMetadataOverview() {
  var hospitalId = currentMetadataHospitalId();
  var dbName = selectedMetadataDatabase();
  metadataHospitalValue.textContent = hospitalId;
  try {
    var response = await fetch(
      "/api/metadata/overview?hospital_id=" + encodeURIComponent(hospitalId)
      + "&db_name=" + encodeURIComponent(dbName)
    );
    var data = await readMetadataResponse(response);
    if (!response.ok) throw new Error(data.detail || "数据库结构状态读取失败");
    renderMetadataOverview(data);
    return data;
  } catch (error) {
    setMetadataNotice("数据库结构状态读取失败：" + error.message + "。请稍后重试或检查系统自检。", "failed");
    throw error;
  }
}

async function loadMetadataSources() {
  if (metadataState.loadingSources) return;
  metadataState.loadingSources = true;
  metadataRefreshConnectionButton.disabled = true;
  metadataConnectionSummary.textContent = "正在检查数据源连接...";
  try {
    var response = await fetch("/api/mcp/dbhub/sources");
    var data = await readMetadataResponse(response);
    if (!response.ok) throw new Error(data.detail || "数据源连接检查失败");
    var normalized = (data.sources || []).map(normalizeMetadataSource);
    metadataState.sources = normalized;
    updateMetadataBusinessSources(normalized);
    renderMetadataSources(normalized);
    metadataConnectionSummary.textContent = normalized.length + " 个数据源连接正常";
    return normalized;
  } catch (error) {
    metadataState.sources = [];
    metadataConnectionSummary.textContent = "连接检查失败，可展开后重新检查";
    metadataSourceList.innerHTML = "";
    var failed = document.createElement("div");
    failed.className = "metadata-empty";
    failed.textContent = "数据源连接失败：" + String(error.message || error);
    metadataSourceList.appendChild(failed);
    throw error;
  } finally {
    metadataState.loadingSources = false;
    metadataRefreshConnectionButton.disabled = false;
  }
}

async function syncMetadataStructure() {
  if (metadataState.syncing) return;
  metadataState.syncing = true;
  metadataSyncButton.disabled = true;
  metadataSyncButton.textContent = "正在同步数据库结构";
  setMetadataNotice("正在读取医院业务库的表和字段结构，请勿重复操作...", "");
  try {
    var response = await fetch("/api/metadata/sync", {
      method: "POST",
      headers: {"Content-Type": "application/json"},
      body: JSON.stringify({
        hospital_id: currentMetadataHospitalId(),
        db_name: selectedMetadataDatabase(),
        source: "dbhub",
      }),
    });
    var data = await readMetadataResponse(response);
    if (!response.ok) throw new Error(data.detail || "数据库结构同步失败");
    await loadMetadataOverview();
    setMetadataNotice(
      "数据库结构同步完成：" + data.table_count + " 个数据库对象、"
      + data.column_count + " 个指标依赖字段，发现 "
      + (data.changes || []).length + " 项变化。",
      "success"
    );
  } catch (error) {
    setMetadataNotice(
      "数据库结构同步失败：" + error.message + "。上一次成功结果未受影响，请检查连接后重试。",
      "failed"
    );
  } finally {
    metadataState.syncing = false;
    metadataSyncButton.disabled = !metadataState.sources.some(function(source) {
      return source.name === selectedMetadataDatabase() && source.name !== "wiki_agent_runtime";
    });
    metadataSyncButton.textContent = "同步数据库结构";
  }
}

async function activateMetadataPage() {
  if (!terminologyWorkspace.hidden) {
    await window.activateTerminologyWorkspace();
    return;
  }
  metadataHospitalValue.textContent = currentMetadataHospitalId();
  setMetadataNotice("正在读取数据库结构状态...", "");
  await Promise.allSettled([loadMetadataSources(), loadMetadataOverview()]);
}

window.activateMetadataPage = activateMetadataPage;

metadataSyncButton.addEventListener("click", syncMetadataStructure);
metadataRefreshConnectionButton.addEventListener("click", loadMetadataSources);
metadataDbSelect.addEventListener("change", loadMetadataOverview);
hospitalIdInput.addEventListener("change", function() {
  if (!metadataPage.hidden) activateMetadataPage();
});
