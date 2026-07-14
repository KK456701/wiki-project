"use strict";

var metadataStructureTab = document.getElementById("metadataStructureTab");
var terminologyTab = document.getElementById("terminologyTab");
var packageExchangeTab = document.getElementById("packageExchangeTab");
var metadataStructurePanel = document.getElementById("metadataStructurePanel");
var terminologyWorkspace = document.getElementById("terminologyWorkspace");
var packageExchangeWorkspace = document.getElementById("packageExchangeWorkspace");
var metadataPrimaryActions = document.getElementById("metadataPrimaryActions");
var terminologySearchInput = document.getElementById("terminologySearchInput");
var terminologyRuleFilter = document.getElementById("terminologyRuleFilter");
var terminologyRefreshButton = document.getElementById("terminologyRefreshButton");
var terminologyAdminButton = document.getElementById("terminologyAdminButton");
var terminologyNotice = document.getElementById("terminologyNotice");
var terminologyConceptList = document.getElementById("terminologyConceptList");
var terminologyConceptCount = document.getElementById("terminologyConceptCount");
var terminologyDetailEmpty = document.getElementById("terminologyDetailEmpty");
var terminologyDetail = document.getElementById("terminologyDetail");
var terminologyConceptCode = document.getElementById("terminologyConceptCode");
var terminologyConceptName = document.getElementById("terminologyConceptName");
var terminologyConceptDefinition = document.getElementById("terminologyConceptDefinition");
var terminologyConceptType = document.getElementById("terminologyConceptType");
var terminologyAliasList = document.getElementById("terminologyAliasList");
var terminologyReviewQueue = document.getElementById("terminologyReviewQueue");
var terminologyHospitalMappings = document.getElementById("terminologyHospitalMappings");
var terminologyMappingHospital = document.getElementById("terminologyMappingHospital");
var terminologyRuleLinks = document.getElementById("terminologyRuleLinks");
var terminologyReleaseList = document.getElementById("terminologyReleaseList");
var terminologyNewAliasButton = document.getElementById("terminologyNewAliasButton");
var terminologyAliasForm = document.getElementById("terminologyAliasForm");
var terminologyAliasText = document.getElementById("terminologyAliasText");
var terminologyAliasRelation = document.getElementById("terminologyAliasRelation");
var terminologyAliasSqlSafe = document.getElementById("terminologyAliasSqlSafe");
var terminologyAliasCancel = document.getElementById("terminologyAliasCancel");
var terminologyNewMappingButton = document.getElementById("terminologyNewMappingButton");
var terminologyMappingForm = document.getElementById("terminologyMappingForm");
var terminologyMappingSystem = document.getElementById("terminologyMappingSystem");
var terminologyMappingCode = document.getElementById("terminologyMappingCode");
var terminologyMappingName = document.getElementById("terminologyMappingName");
var terminologyMappingValue = document.getElementById("terminologyMappingValue");
var terminologyMappingCancel = document.getElementById("terminologyMappingCancel");
var terminologyPublishButton = document.getElementById("terminologyPublishButton");
var terminologyTestForm = document.getElementById("terminologyTestForm");
var terminologyTestInput = document.getElementById("terminologyTestInput");
var terminologyTestResult = document.getElementById("terminologyTestResult");

var terminologyState = {
  concepts: [],
  selectedCode: "",
  detail: null,
  active: false,
};

var terminologyTypeNames = {
  indicator: "指标",
  diagnosis: "诊断",
  department: "科室",
  staff_role: "人员角色",
  procedure: "操作",
  time_window: "时间范围",
  status: "状态",
  data_value: "数据值",
  business_concept: "业务概念",
};

var terminologyRelationNames = {
  exact: "完全同义",
  abbreviation: "简称",
  colloquial: "口语表达",
  related: "相关词",
  value_mapping: "本院映射",
  forbidden: "禁止替换",
};

function terminologyHospitalId() {
  return (hospitalIdInput.value || "hospital_001").trim() || "hospital_001";
}

function setTerminologyNotice(message, failed) {
  terminologyNotice.textContent = message;
  terminologyNotice.className = "terminology-notice" + (failed ? " failed" : "");
}

function terminologyAdminHeaders() {
  return {"Authorization": "Bearer " + adminToken, "Content-Type": "application/json"};
}

async function terminologyApi(url, options) {
  var response = await fetch(url, options || {});
  var data = await response.json();
  if (!response.ok) throw new Error(data.detail || "术语服务调用失败");
  return data;
}

function updateTerminologyPermissions() {
  document.querySelectorAll(".term-admin-action").forEach(function(button) {
    button.hidden = !adminToken;
  });
  terminologyAdminButton.textContent = adminToken ? "维护模式已开启" : "管理员维护";
  terminologyAdminButton.classList.toggle("active", !!adminToken);
}

function switchDataFoundationTab(target) {
  var terms = target === "terminology";
  var exchange = target === "packageExchange";
  var metadata = !terms && !exchange;
  metadataStructureTab.classList.toggle("active", metadata);
  terminologyTab.classList.toggle("active", terms);
  packageExchangeTab.classList.toggle("active", exchange);
  metadataStructureTab.setAttribute("aria-selected", metadata ? "true" : "false");
  terminologyTab.setAttribute("aria-selected", terms ? "true" : "false");
  packageExchangeTab.setAttribute("aria-selected", exchange ? "true" : "false");
  metadataStructurePanel.hidden = !metadata;
  terminologyWorkspace.hidden = !terms;
  packageExchangeWorkspace.hidden = !exchange;
  metadataPrimaryActions.hidden = !metadata;
  if (terms) activateTerminologyWorkspace();
  if (exchange && window.activatePackageExchangeWorkspace) window.activatePackageExchangeWorkspace();
}

async function loadTerminologyConcepts(selectCode) {
  var params = new URLSearchParams();
  if (terminologySearchInput.value.trim()) params.set("query", terminologySearchInput.value.trim());
  if (terminologyRuleFilter.value.trim()) params.set("rule_id", terminologyRuleFilter.value.trim());
  setTerminologyNotice("正在读取标准概念...", false);
  try {
    var data = await terminologyApi("/api/terminology/concepts?" + params.toString());
    terminologyState.concepts = data.items || [];
    terminologyConceptCount.textContent = String(data.total || 0);
    renderTerminologyConcepts();
    var target = selectCode || terminologyState.selectedCode;
    if (target && terminologyState.concepts.some(function(item) { return item.concept_code === target; })) {
      await loadTerminologyConcept(target);
    } else if (terminologyState.concepts.length) {
      await loadTerminologyConcept(terminologyState.concepts[0].concept_code);
    } else {
      terminologyState.selectedCode = "";
      terminologyDetail.hidden = true;
      terminologyDetailEmpty.hidden = false;
      terminologyDetailEmpty.textContent = "没有找到符合条件的标准概念。";
      setTerminologyNotice("当前筛选条件没有结果。", false);
    }
  } catch (error) {
    setTerminologyNotice("术语列表读取失败：" + error.message, true);
  }
}

function renderTerminologyConcepts() {
  terminologyConceptList.innerHTML = "";
  terminologyState.concepts.forEach(function(concept) {
    var button = document.createElement("button");
    button.type = "button";
    button.className = "terminology-concept-item" + (concept.concept_code === terminologyState.selectedCode ? " active" : "");
    var name = document.createElement("strong");
    name.textContent = concept.canonical_name;
    var meta = document.createElement("span");
    meta.textContent = concept.concept_code + " · " + (terminologyTypeNames[concept.concept_type] || concept.concept_type) + " · " + (concept.alias_count || 0) + " 个同义词";
    button.append(name, meta);
    button.addEventListener("click", function() { loadTerminologyConcept(concept.concept_code); });
    terminologyConceptList.appendChild(button);
  });
}

async function loadTerminologyConcept(conceptCode) {
  try {
    var data = await terminologyApi(
      "/api/terminology/concepts/" + encodeURIComponent(conceptCode)
      + "?hospital_id=" + encodeURIComponent(terminologyHospitalId())
    );
    terminologyState.selectedCode = conceptCode;
    terminologyState.detail = data;
    renderTerminologyConcepts();
    renderTerminologyDetail(data);
    setTerminologyNotice("已读取“" + data.canonical_name + "”及当前医院映射。", false);
  } catch (error) {
    setTerminologyNotice("概念详情读取失败：" + error.message, true);
  }
}

function renderTerminologyDetail(detail) {
  terminologyDetailEmpty.hidden = true;
  terminologyDetail.hidden = false;
  terminologyConceptCode.textContent = detail.concept_code;
  terminologyConceptName.textContent = detail.canonical_name;
  terminologyConceptDefinition.textContent = detail.definition || "尚未填写定义。";
  terminologyConceptType.textContent = terminologyTypeNames[detail.concept_type] || detail.concept_type;
  terminologyMappingHospital.textContent = "当前医院 " + terminologyHospitalId();
  renderTerminologyAliases(detail.aliases || []);
  renderTerminologyMappings(detail.hospital_mappings || []);
  renderTerminologyRuleLinks(detail.rule_links || []);
  loadTerminologyReleases();
  updateTerminologyPermissions();
}

function safetyBadge(text, safe) {
  var badge = document.createElement("span");
  badge.className = "term-safety" + (safe ? " safe" : "");
  badge.textContent = text + "：" + (safe ? "是" : "否");
  return badge;
}

function renderTerminologyAliases(aliases) {
  terminologyAliasList.innerHTML = "";
  var pending = 0;
  if (!aliases.length) terminologyAliasList.appendChild(termEmpty("尚无同义词。"));
  aliases.forEach(function(alias) {
    if (alias.approval_status === "pending") pending += 1;
    var row = termRecord(alias.alias_text, (terminologyRelationNames[alias.relation_type] || alias.relation_type) + " · " + alias.approval_status);
    row.actions.append(
      safetyBadge("可检索", !!alias.retrieval_enabled),
      safetyBadge("可进 SQL", !!alias.sql_safe)
    );
    if (alias.approval_status === "pending" && adminToken) {
      var approve = document.createElement("button");
      approve.type = "button";
      approve.textContent = "批准";
      approve.addEventListener("click", function() { approveTerminologyAlias(alias.id); });
      row.actions.appendChild(approve);
    }
    terminologyAliasList.appendChild(row.element);
  });
  terminologyReviewQueue.textContent = pending ? "待审核候选词：" + pending + " 条" : "当前没有待审核候选词。";
}

function renderTerminologyMappings(mappings) {
  terminologyHospitalMappings.innerHTML = "";
  if (!mappings.length) terminologyHospitalMappings.appendChild(termEmpty("当前医院尚未配置该概念的编码和值。"));
  mappings.forEach(function(mapping) {
    var row = termRecord(mapping.local_name, mapping.code_system + " · " + (mapping.local_code || "无本院编码") + " · 数据库值 " + mapping.local_value);
    var status = document.createElement("span");
    status.className = "term-status " + mapping.approval_status;
    status.textContent = mapping.approval_status === "approved" ? "已生效" : "待审核";
    row.actions.appendChild(status);
    if (mapping.approval_status === "pending" && adminToken) {
      var approve = document.createElement("button");
      approve.type = "button";
      approve.textContent = "批准";
      approve.addEventListener("click", function() { approveTerminologyMapping(mapping.id); });
      row.actions.appendChild(approve);
    }
    terminologyHospitalMappings.appendChild(row.element);
  });
}

function renderTerminologyRuleLinks(links) {
  terminologyRuleLinks.innerHTML = "";
  if (!links.length) terminologyRuleLinks.appendChild(termEmpty("当前概念尚未关联指标。"));
  links.forEach(function(link) {
    var field = link.business_field_key ? " · 业务字段 " + link.business_field_key : "";
    terminologyRuleLinks.appendChild(termRecord(link.index_code, "使用位置 " + link.usage_section + field).element);
  });
}

function termRecord(title, meta) {
  var element = document.createElement("div");
  element.className = "term-record";
  var main = document.createElement("div");
  main.className = "term-record-main";
  var strong = document.createElement("strong");
  strong.textContent = title;
  var detail = document.createElement("div");
  detail.className = "term-record-meta";
  detail.textContent = meta;
  main.append(strong, detail);
  var actions = document.createElement("div");
  actions.className = "term-record-actions";
  element.append(main, actions);
  return {element: element, actions: actions};
}

function termEmpty(message) {
  var empty = document.createElement("div");
  empty.className = "terminology-empty";
  empty.style.minHeight = "100px";
  empty.textContent = message;
  return empty;
}

async function createTerminologyAlias(event) {
  event.preventDefault();
  if (!terminologyState.selectedCode) return;
  try {
    await terminologyApi("/api/terminology/aliases", {
      method: "POST",
      headers: {"Authorization": "Bearer " + adminToken, "Content-Type": "application/json"},
      body: JSON.stringify({
        hospital_id: terminologyHospitalId(),
        concept_code: terminologyState.selectedCode,
        alias_text: terminologyAliasText.value.trim(),
        relation_type: terminologyAliasRelation.value,
        retrieval_enabled: terminologyAliasRelation.value !== "forbidden",
        sql_safe: terminologyAliasSqlSafe.checked,
        source_reference: "terminology-workbench",
        created_by: currentUser && currentUser.accountId || "admin",
      }),
    });
    terminologyAliasForm.hidden = true;
    terminologyAliasForm.reset();
    await loadTerminologyConcept(terminologyState.selectedCode);
  } catch (error) { setTerminologyNotice("候选词保存失败：" + error.message, true); }
}

async function approveTerminologyAlias(aliasId) {
  try {
    await terminologyApi("/api/terminology/aliases/" + aliasId + "/approve", {
      method: "POST", headers: terminologyAdminHeaders(),
      body: JSON.stringify({actor_id: currentUser && currentUser.accountId || "admin"}),
    });
    await loadTerminologyConcept(terminologyState.selectedCode);
  } catch (error) { setTerminologyNotice("候选词审批失败：" + error.message, true); }
}

async function createTerminologyMapping(event) {
  event.preventDefault();
  try {
    await terminologyApi("/api/terminology/hospital-mappings", {
      method: "POST", headers: terminologyAdminHeaders(),
      body: JSON.stringify({
        hospital_id: terminologyHospitalId(),
        concept_code: terminologyState.selectedCode,
        code_system: terminologyMappingSystem.value.trim(),
        local_code: terminologyMappingCode.value.trim(),
        local_name: terminologyMappingName.value.trim(),
        local_value: terminologyMappingValue.value.trim(),
        created_by: currentUser && currentUser.accountId || "admin",
      }),
    });
    terminologyMappingForm.hidden = true;
    terminologyMappingForm.reset();
    await loadTerminologyConcept(terminologyState.selectedCode);
  } catch (error) { setTerminologyNotice("本院映射保存失败：" + error.message, true); }
}

async function approveTerminologyMapping(mappingId) {
  try {
    await terminologyApi("/api/terminology/hospital-mappings/" + mappingId + "/approve", {
      method: "POST", headers: terminologyAdminHeaders(),
      body: JSON.stringify({actor_id: currentUser && currentUser.accountId || "admin"}),
    });
    await loadTerminologyConcept(terminologyState.selectedCode);
  } catch (error) { setTerminologyNotice("本院映射审批失败：" + error.message, true); }
}

async function loadTerminologyReleases() {
  try {
    var data = await terminologyApi("/api/terminology/releases");
    terminologyReleaseList.innerHTML = "";
    (data.items || []).forEach(function(release) {
      var row = termRecord("v" + release.version + " · " + release.release_id, (release.status === "active" ? "当前生效" : "历史版本") + " · " + release.created_at);
      if (release.status !== "active" && adminToken) {
        var restore = document.createElement("button");
        restore.type = "button";
        restore.className = "ghost";
        restore.textContent = "回退到此版本";
        restore.addEventListener("click", function() { restoreTerminologyRelease(release.release_id); });
        row.actions.appendChild(restore);
      }
      terminologyReleaseList.appendChild(row.element);
    });
  } catch (error) { terminologyReleaseList.textContent = "版本读取失败：" + error.message; }
}

async function publishTerminologyRelease() {
  if (!confirm("发布后，新术语将进入对话识别链路。确认发布？")) return;
  try {
    await terminologyApi("/api/terminology/releases/publish", {
      method: "POST", headers: terminologyAdminHeaders(),
      body: JSON.stringify({actor_id: currentUser && currentUser.accountId || "admin"}),
    });
    await loadTerminologyReleases();
    setTerminologyNotice("术语版本已发布。", false);
  } catch (error) { setTerminologyNotice("术语发布失败：" + error.message, true); }
}

async function restoreTerminologyRelease(releaseId) {
  if (!confirm("确认将运行时术语回退到 " + releaseId + "？历史版本不会删除。")) return;
  try {
    await terminologyApi("/api/terminology/releases/" + encodeURIComponent(releaseId) + "/restore", {
      method: "POST", headers: terminologyAdminHeaders(),
      body: JSON.stringify({actor_id: currentUser && currentUser.accountId || "admin"}),
    });
    await loadTerminologyConcepts(terminologyState.selectedCode);
  } catch (error) { setTerminologyNotice("术语版本回退失败：" + error.message, true); }
}

async function runTerminologyTest(event) {
  event.preventDefault();
  terminologyTestResult.textContent = "正在识别...";
  try {
    var result = await terminologyApi("/api/terminology/test", {
      method: "POST", headers: {"Content-Type": "application/json"},
      body: JSON.stringify({hospital_id: terminologyHospitalId(), text: terminologyTestInput.value.trim()}),
    });
    var terms = (result.matches || []).map(function(match) {
      return match.matched_text + " → " + match.canonical_name + "（" + (terminologyRelationNames[match.relation_type] || match.relation_type) + "，SQL " + (match.sql_safe ? "可用" : "不可用") + "）";
    });
    terminologyTestResult.textContent = "标准化：" + result.normalized_text + "。" + (terms.length ? terms.join("；") : "未命中术语。") + " 结论：" + (result.sql_eligible ? "可继续检查 SQL 条件。" : "不能直接用于 SQL，请确认歧义或本院映射。") + " 版本：" + result.release_version;
  } catch (error) { terminologyTestResult.textContent = "识别测试失败：" + error.message; }
}

function switchTerminologyDetailPanel(name) {
  document.querySelectorAll(".term-detail-tab").forEach(function(tab) {
    var active = tab.dataset.termPanel === name;
    tab.classList.toggle("active", active);
    tab.setAttribute("aria-selected", active ? "true" : "false");
  });
  document.querySelectorAll("[data-term-panel-name]").forEach(function(panel) {
    panel.hidden = panel.dataset.termPanelName !== name;
  });
}

async function activateTerminologyWorkspace() {
  terminologyState.active = true;
  updateTerminologyPermissions();
  await loadTerminologyConcepts(terminologyState.selectedCode);
}

function activateTerminologyAdminMode() {
  updateTerminologyPermissions();
  if (terminologyState.selectedCode) loadTerminologyConcept(terminologyState.selectedCode);
}

window.activateTerminologyWorkspace = activateTerminologyWorkspace;
window.activateTerminologyAdminMode = activateTerminologyAdminMode;

metadataStructureTab.addEventListener("click", function() { switchDataFoundationTab("metadata"); });
terminologyTab.addEventListener("click", function() { switchDataFoundationTab("terminology"); });
terminologyRefreshButton.addEventListener("click", function() { loadTerminologyConcepts(); });
terminologySearchInput.addEventListener("input", function() { clearTimeout(terminologyState.searchTimer); terminologyState.searchTimer = setTimeout(function() { loadTerminologyConcepts(); }, 250); });
terminologyRuleFilter.addEventListener("change", function() { loadTerminologyConcepts(); });
terminologyAdminButton.addEventListener("click", function() {
  if (adminToken) { activateTerminologyAdminMode(); return; }
  requireAdminThenOpen("terminology");
});
document.querySelectorAll(".term-detail-tab").forEach(function(tab) { tab.addEventListener("click", function() { switchTerminologyDetailPanel(tab.dataset.termPanel); }); });
terminologyNewAliasButton.addEventListener("click", function() { terminologyAliasForm.hidden = false; terminologyAliasText.focus(); });
terminologyAliasCancel.addEventListener("click", function() { terminologyAliasForm.hidden = true; });
terminologyAliasForm.addEventListener("submit", createTerminologyAlias);
terminologyAliasRelation.addEventListener("change", function() { if (["related", "forbidden"].includes(terminologyAliasRelation.value)) terminologyAliasSqlSafe.checked = false; terminologyAliasSqlSafe.disabled = ["related", "forbidden"].includes(terminologyAliasRelation.value); });
terminologyNewMappingButton.addEventListener("click", function() { terminologyMappingForm.hidden = false; terminologyMappingSystem.focus(); });
terminologyMappingCancel.addEventListener("click", function() { terminologyMappingForm.hidden = true; });
terminologyMappingForm.addEventListener("submit", createTerminologyMapping);
terminologyPublishButton.addEventListener("click", publishTerminologyRelease);
terminologyTestForm.addEventListener("submit", runTerminologyTest);
hospitalIdInput.addEventListener("change", function() { if (terminologyState.active && !terminologyWorkspace.hidden) loadTerminologyConcept(terminologyState.selectedCode); });
