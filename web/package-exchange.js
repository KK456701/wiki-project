"use strict";

var packageExchangeAuthorizeButton = document.getElementById("packageExchangeAuthorizeButton");
var packageExchangeNotice = document.getElementById("packageExchangeNotice");
var metadataScopeList = document.getElementById("metadataScopeList");
var metadataScopeCount = document.getElementById("metadataScopeCount");
var metadataScopeSaveButton = document.getElementById("metadataScopeSaveButton");
var metadataScopePreviewButton = document.getElementById("metadataScopePreviewButton");
var metadataExportPreview = document.getElementById("metadataExportPreview");
var hospitalFeedbackExportButton = document.getElementById("hospitalFeedbackExportButton");
var companyReleaseImportInput = document.getElementById("companyReleaseImportInput");
var companyReleaseImportButton = document.getElementById("companyReleaseImportButton");
var companyReleaseRefreshButton = document.getElementById("companyReleaseRefreshButton");
var companyReleaseFileName = document.getElementById("companyReleaseFileName");
var companyReleaseImportList = document.getElementById("companyReleaseImportList");
var companyReleaseImportDetail = document.getElementById("companyReleaseImportDetail");

var packageExchangeState = {
  active: false,
  scope: null,
  imports: [],
  selectedImportId: "",
};

var packageStatusNames = {
  ready_for_adaptation: "已验签，待本院适配",
  quarantined: "已隔离，禁止使用",
  incompatible: "系统版本不兼容",
  verified: "签名有效",
  legacy_unsigned: "旧版未签名",
  compatible: "版本兼容",
  review_required: "需要人工复核",
};

function packageHospitalId() {
  return (hospitalIdInput.value || "hospital_001").trim() || "hospital_001";
}

function packageDatabaseName() {
  return (metadataDbSelect.value || "hospital_demo_data").trim() || "hospital_demo_data";
}

function packageActorId() {
  return currentUser && currentUser.accountId ? currentUser.accountId : "admin";
}

function setPackageExchangeNotice(message, state) {
  packageExchangeNotice.textContent = message;
  packageExchangeNotice.className = "package-exchange-notice" + (state ? " " + state : "");
}

function packageErrorMessage(data, fallback) {
  if (!data) return fallback;
  if (typeof data.detail === "string") return data.detail;
  if (data.detail && data.detail.message) return data.detail.message;
  if (data.message) return data.message;
  return fallback;
}

async function packageJsonApi(url, options) {
  var response = await fetch(url, options || {});
  var data = await response.json().catch(function() { return {}; });
  if (!response.ok) throw new Error(packageErrorMessage(data, "离线包服务调用失败"));
  return data;
}

function packageAdminHeaders(contentType) {
  var headers = {"Authorization": "Bearer " + adminToken};
  if (contentType) headers["Content-Type"] = contentType;
  return headers;
}

function requirePackageAdmin() {
  if (adminToken) return true;
  requireAdminThenOpen("packageExchange");
  return false;
}

function updatePackageExchangePermissions() {
  var authorized = !!adminToken;
  packageExchangeAuthorizeButton.textContent = authorized ? "管理员权限已开启" : "管理员授权";
  packageExchangeAuthorizeButton.classList.toggle("active", authorized);
  metadataScopeSaveButton.disabled = !authorized;
  hospitalFeedbackExportButton.disabled = !authorized;
  companyReleaseImportButton.disabled = !authorized || !companyReleaseImportInput.files.length;
  companyReleaseRefreshButton.disabled = !authorized;
}

async function loadMetadataExportScope() {
  metadataScopeList.innerHTML = '<div class="package-empty">正在读取已同步的数据库结构...</div>';
  var url = "/api/kb/export/scope?hospital_id=" + encodeURIComponent(packageHospitalId()) +
    "&db_name=" + encodeURIComponent(packageDatabaseName());
  try {
    packageExchangeState.scope = await packageJsonApi(url);
    renderMetadataExportScope();
    await loadMetadataExportPreview();
  } catch (error) {
    metadataScopeList.textContent = "读取失败：" + error.message;
    setPackageExchangeNotice("无法读取导出范围。请先在“数据库结构”页同步本院业务库结构。", "failed");
  }
}

function renderMetadataExportScope() {
  metadataScopeList.innerHTML = "";
  var tables = packageExchangeState.scope && packageExchangeState.scope.tables || [];
  if (!tables.length) {
    metadataScopeList.innerHTML = '<div class="package-empty">暂无可选字段。请先同步数据库结构。</div>';
    updateMetadataScopeCount();
    return;
  }
  tables.forEach(function(table, tableIndex) {
    var details = document.createElement("details");
    details.className = "metadata-scope-group";
    details.open = tableIndex < 2 || table.columns.some(function(column) { return column.selected; });

    var summary = document.createElement("summary");
    var title = document.createElement("span");
    title.className = "metadata-scope-title";
    var strong = document.createElement("strong");
    strong.textContent = table.table_comment ? table.table_comment + "（" + table.table_name + "）" : table.table_name;
    var small = document.createElement("small");
    small.textContent = table.columns.length + " 个字段";
    title.append(strong, small);

    var selectAllLabel = document.createElement("label");
    selectAllLabel.className = "metadata-scope-select-all";
    var selectAll = document.createElement("input");
    selectAll.type = "checkbox";
    selectAll.checked = table.columns.every(function(column) { return column.selected; });
    selectAll.indeterminate = !selectAll.checked && table.columns.some(function(column) { return column.selected; });
    selectAll.setAttribute("aria-label", "选择 " + table.table_name + " 的全部字段");
    selectAll.addEventListener("click", function(event) { event.stopPropagation(); });
    selectAll.addEventListener("change", function() {
      details.querySelectorAll("input[data-scope-column]").forEach(function(input) { input.checked = selectAll.checked; });
      updateMetadataScopeCount();
    });
    selectAllLabel.addEventListener("click", function(event) { event.stopPropagation(); });
    selectAllLabel.append(selectAll, document.createTextNode(" 全选"));
    summary.append(title, selectAllLabel);

    var columns = document.createElement("div");
    columns.className = "metadata-scope-columns";
    table.columns.forEach(function(column) {
      var label = document.createElement("label");
      label.className = "metadata-scope-column";
      var input = document.createElement("input");
      input.type = "checkbox";
      input.checked = !!column.selected;
      input.dataset.scopeColumn = "true";
      input.dataset.tableName = table.table_name;
      input.dataset.columnName = column.column_name;
      input.addEventListener("change", function() {
        var all = Array.from(details.querySelectorAll("input[data-scope-column]"));
        selectAll.checked = all.every(function(item) { return item.checked; });
        selectAll.indeterminate = !selectAll.checked && all.some(function(item) { return item.checked; });
        updateMetadataScopeCount();
      });
      var description = document.createElement("span");
      var name = document.createElement("strong");
      name.textContent = column.column_comment ? column.column_comment + "（" + column.column_name + "）" : column.column_name;
      var type = document.createElement("small");
      type.textContent = column.column_type || column.data_type || "类型未知";
      description.append(name, type);
      label.append(input, description);
      columns.appendChild(label);
    });
    details.append(summary, columns);
    metadataScopeList.appendChild(details);
  });
  updateMetadataScopeCount();
}

function selectedMetadataScope() {
  return Array.from(metadataScopeList.querySelectorAll("input[data-scope-column]:checked")).map(function(input) {
    return {table_name: input.dataset.tableName, column_name: input.dataset.columnName};
  });
}

function updateMetadataScopeCount() {
  var count = selectedMetadataScope().length;
  metadataScopeCount.textContent = count + " 个字段";
}

async function saveMetadataExportScope() {
  if (!requirePackageAdmin()) return;
  var selections = selectedMetadataScope();
  if (!selections.length) {
    setPackageExchangeNotice("至少选择一个允许带出院区的字段。", "failed");
    return;
  }
  metadataScopeSaveButton.disabled = true;
  try {
    await packageJsonApi("/api/kb/export/scope", {
      method: "PUT",
      headers: packageAdminHeaders("application/json"),
      body: JSON.stringify({
        hospital_id: packageHospitalId(),
        db_name: packageDatabaseName(),
        selections: selections,
        actor_id: packageActorId(),
      }),
    });
    setPackageExchangeNotice("允许范围已保存。当前反馈包会精确到所选字段。", "success");
    await loadMetadataExportScope();
  } catch (error) {
    setPackageExchangeNotice("保存失败：" + error.message, "failed");
  } finally {
    updatePackageExchangePermissions();
  }
}

async function loadMetadataExportPreview() {
  var url = "/api/kb/export/preview?hospital_id=" + encodeURIComponent(packageHospitalId()) +
    "&db_name=" + encodeURIComponent(packageDatabaseName());
  try {
    renderMetadataExportPreview(await packageJsonApi(url));
  } catch (error) {
    metadataExportPreview.textContent = "预览失败：" + error.message;
  }
}

function renderMetadataExportPreview(preview) {
  metadataExportPreview.innerHTML = "";
  var counts = document.createElement("div");
  counts.className = "package-preview-counts";
  [[preview.selected_table_count || 0, "业务表"], [preview.selected_column_count || 0, "字段"]].forEach(function(item) {
    var box = document.createElement("div");
    var value = document.createElement("strong"); value.textContent = String(item[0]);
    var label = document.createElement("span"); label.textContent = item[1];
    box.append(value, label); counts.appendChild(box);
  });
  metadataExportPreview.appendChild(counts);

  var list = document.createElement("ul");
  list.className = "package-preview-list";
  (preview.tables || []).forEach(function(table) {
    var item = document.createElement("li");
    item.textContent = table.table_name + "：" + table.columns.map(function(column) { return column.column_name; }).join("、");
    list.appendChild(item);
  });
  if (list.children.length) metadataExportPreview.appendChild(list);

  var excluded = document.createElement("div");
  excluded.className = "package-exclusion-list";
  var heading = document.createElement("strong"); heading.textContent = "明确不导出";
  var text = document.createElement("span"); text.textContent = (preview.excluded_content || []).join("、");
  excluded.append(heading, text);
  metadataExportPreview.appendChild(excluded);
}

async function downloadHospitalFeedbackPackage() {
  if (!requirePackageAdmin()) return;
  hospitalFeedbackExportButton.disabled = true;
  var url = "/api/kb/export?hospital_id=" + encodeURIComponent(packageHospitalId()) +
    "&db_name=" + encodeURIComponent(packageDatabaseName());
  try {
    var response = await fetch(url, {headers: packageAdminHeaders()});
    if (!response.ok) {
      var errorData = await response.json().catch(function() { return {}; });
      throw new Error(packageErrorMessage(errorData, "反馈包生成失败"));
    }
    var blob = await response.blob();
    var disposition = response.headers.get("Content-Disposition") || "";
    var match = disposition.match(/filename="?([^";]+)"?/i);
    var filename = match ? match[1] : packageHospitalId() + "_feedback.medfeedback";
    var link = document.createElement("a");
    link.href = URL.createObjectURL(blob);
    link.download = filename;
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(link.href);
    setPackageExchangeNotice("医院反馈包已签名并下载。可通过院内受控介质交付公司。", "success");
  } catch (error) {
    setPackageExchangeNotice("生成失败：" + error.message, "failed");
  } finally {
    updatePackageExchangePermissions();
  }
}

async function importCompanyReleasePackage() {
  if (!requirePackageAdmin()) return;
  var file = companyReleaseImportInput.files && companyReleaseImportInput.files[0];
  if (!file) {
    setPackageExchangeNotice("请先选择公司发布包。", "failed");
    return;
  }
  companyReleaseImportButton.disabled = true;
  try {
    var result = await packageJsonApi("/api/kb/hospital/releases/imports", {
      method: "POST",
      headers: packageAdminHeaders("application/zip"),
      body: await file.arrayBuffer(),
    });
    packageExchangeState.selectedImportId = result.import_id;
    setPackageExchangeNotice(
      result.status === "ready_for_adaptation"
        ? "公司发布包签名有效，已进入待适配区；当前生效指标未被修改。"
        : "公司发布包已隔离，请根据签名和兼容性提示处理。",
      result.status === "ready_for_adaptation" ? "success" : "failed"
    );
    companyReleaseImportInput.value = "";
    companyReleaseFileName.textContent = "尚未选择文件";
    await loadCompanyReleaseImports();
  } catch (error) {
    setPackageExchangeNotice("导入失败：" + error.message, "failed");
  } finally {
    updatePackageExchangePermissions();
  }
}

async function loadCompanyReleaseImports() {
  updatePackageExchangePermissions();
  if (!adminToken) {
    companyReleaseImportList.innerHTML = '<div class="package-empty">管理员授权后可查看历史导入记录。</div>';
    return;
  }
  companyReleaseImportList.innerHTML = '<div class="package-empty">正在读取导入记录...</div>';
  try {
    var data = await packageJsonApi("/api/kb/hospital/releases/imports", {headers: packageAdminHeaders()});
    packageExchangeState.imports = data.items || [];
    renderCompanyReleaseImports();
    if (packageExchangeState.selectedImportId) await showCompanyReleaseImport(packageExchangeState.selectedImportId);
  } catch (error) {
    companyReleaseImportList.textContent = "读取失败：" + error.message;
  }
}

function renderCompanyReleaseImports() {
  companyReleaseImportList.innerHTML = "";
  if (!packageExchangeState.imports.length) {
    companyReleaseImportList.innerHTML = '<div class="package-empty">暂无公司发布包导入记录。</div>';
    return;
  }
  packageExchangeState.imports.forEach(function(item) {
    var button = document.createElement("button");
    button.type = "button";
    button.className = "package-import-item" + (item.import_id === packageExchangeState.selectedImportId ? " active" : "");
    var title = document.createElement("strong");
    title.textContent = item.release_id || item.package_id || item.import_id;
    var meta = document.createElement("small");
    meta.textContent = (packageStatusNames[item.status] || item.status) + " · " + (item.imported_at || "");
    button.append(title, meta);
    button.addEventListener("click", function() { showCompanyReleaseImport(item.import_id); });
    companyReleaseImportList.appendChild(button);
  });
}

function packageStatusClass(value) {
  if (["quarantined", "incompatible"].includes(value)) return " failed";
  if (["legacy_unsigned", "review_required"].includes(value)) return " warning";
  return "";
}

async function showCompanyReleaseImport(importId) {
  packageExchangeState.selectedImportId = importId;
  renderCompanyReleaseImports();
  companyReleaseImportDetail.innerHTML = '<div class="package-empty">正在读取包详情...</div>';
  try {
    var detail = await packageJsonApi("/api/kb/hospital/releases/imports/" + encodeURIComponent(importId), {headers: packageAdminHeaders()});
    renderCompanyReleaseImportDetail(detail);
  } catch (error) {
    companyReleaseImportDetail.textContent = "读取失败：" + error.message;
  }
}

function renderCompanyReleaseImportDetail(detail) {
  companyReleaseImportDetail.innerHTML = "";
  var title = document.createElement("h4");
  title.textContent = detail.release_id || detail.package_id || detail.import_id;
  var statuses = document.createElement("div");
  statuses.className = "package-status-row";
  [detail.status, detail.signature_status, detail.compatibility_status].forEach(function(value) {
    if (!value) return;
    var badge = document.createElement("span");
    badge.className = "package-status" + packageStatusClass(value);
    badge.textContent = packageStatusNames[value] || value;
    statuses.appendChild(badge);
  });
  var table = document.createElement("table");
  table.className = "package-detail-table";
  var rows = [
    ["导入编号", detail.import_id],
    ["签名密钥", detail.signer_key_id || "未提供"],
    ["包格式", detail.format_version],
    ["包内项目", (detail.items || []).length + " 项"],
    ["兼容说明", detail.compatibility && detail.compatibility.message || "无"],
    ["当前影响", "仅保存到隔离区，未修改本院生效指标和 SQL"],
  ];
  var body = document.createElement("tbody");
  rows.forEach(function(row) {
    var tr = document.createElement("tr");
    var th = document.createElement("th"); th.textContent = row[0];
    var td = document.createElement("td"); td.textContent = String(row[1] || "");
    tr.append(th, td); body.appendChild(tr);
  });
  table.appendChild(body);
  var itemSection = document.createElement("section");
  itemSection.className = "package-release-items";
  var itemTitle = document.createElement("h5");
  itemTitle.textContent = "包内指标与知识项";
  itemSection.appendChild(itemTitle);
  (detail.items || []).forEach(function(item) {
    var row = document.createElement("div");
    row.className = "package-release-item";
    var content = document.createElement("div");
    var name = document.createElement("strong");
    var payload = item.payload || {};
    name.textContent = payload.index_name || payload.rule_name || item.rule_id || item.item_path;
    var meta = document.createElement("small");
    meta.textContent = item.item_type === "rule"
      ? "指标规则 · " + (item.rule_id || "未提供编码")
      : "知识项 · " + item.item_path;
    content.append(name, meta);
    row.appendChild(content);
    if (item.item_type === "rule" && item.rule_id) {
      var action = document.createElement("button");
      action.type = "button";
      action.textContent = "进入本院适配";
      action.disabled = detail.status !== "ready_for_adaptation" ||
        detail.signature_status !== "verified" || detail.compatibility_status !== "compatible";
      if (action.disabled) action.title = "只有签名有效且版本兼容的指标规则才能进入本院适配";
      action.addEventListener("click", function() {
        createIndicatorDraftFromRelease(detail, item, action);
      });
      row.appendChild(action);
    }
    itemSection.appendChild(row);
  });
  if (!(detail.items || []).length) {
    var empty = document.createElement("div");
    empty.className = "package-empty";
    empty.textContent = "发布包中没有可展示的指标或知识项。";
    itemSection.appendChild(empty);
  }
  companyReleaseImportDetail.append(title, statuses, table, itemSection);
}

async function createIndicatorDraftFromRelease(detail, item, button) {
  if (!requirePackageAdmin()) return;
  button.disabled = true;
  button.textContent = "正在创建...";
  try {
    var draft = await packageJsonApi("/api/indicator-drafts/from-release", {
      method: "POST",
      headers: packageAdminHeaders("application/json"),
      body: JSON.stringify({
        import_id: detail.import_id,
        rule_id: item.rule_id,
        hospital_id: packageHospitalId(),
        actor_id: packageActorId(),
      }),
    });
    setPackageExchangeNotice(
      draft.duplicate ? "该指标已有本院适配任务，正在打开。" : "本院适配任务已创建，正在打开指标实施控制台。",
      "success"
    );
    if (window.openIndicatorDraft) window.openIndicatorDraft(draft.draft_id);
    if (window.navigateWorkbench) window.navigateWorkbench("indicator-console");
  } catch (error) {
    setPackageExchangeNotice("创建本院适配任务失败：" + error.message, "failed");
    button.disabled = false;
    button.textContent = "进入本院适配";
  }
}

async function activatePackageExchangeWorkspace() {
  packageExchangeState.active = true;
  updatePackageExchangePermissions();
  await loadMetadataExportScope();
  await loadCompanyReleaseImports();
}

async function activatePackageExchangeAdmin() {
  updatePackageExchangePermissions();
  setPackageExchangeNotice("管理员权限已开启，可以保存白名单、导出反馈包和导入公司发布包。", "success");
  await loadCompanyReleaseImports();
}

window.activatePackageExchangeWorkspace = activatePackageExchangeWorkspace;
window.activatePackageExchangeAdmin = activatePackageExchangeAdmin;

packageExchangeTab.addEventListener("click", function() { switchDataFoundationTab("packageExchange"); });
packageExchangeAuthorizeButton.addEventListener("click", function() {
  if (adminToken) { activatePackageExchangeAdmin(); return; }
  requireAdminThenOpen("packageExchange");
});
metadataScopeSaveButton.addEventListener("click", saveMetadataExportScope);
metadataScopePreviewButton.addEventListener("click", loadMetadataExportPreview);
hospitalFeedbackExportButton.addEventListener("click", downloadHospitalFeedbackPackage);
companyReleaseImportButton.addEventListener("click", importCompanyReleasePackage);
companyReleaseRefreshButton.addEventListener("click", loadCompanyReleaseImports);
companyReleaseImportInput.addEventListener("change", function() {
  var file = companyReleaseImportInput.files && companyReleaseImportInput.files[0];
  companyReleaseFileName.textContent = file ? file.name : "尚未选择文件";
  updatePackageExchangePermissions();
});
hospitalIdInput.addEventListener("change", function() {
  if (packageExchangeState.active && !packageExchangeWorkspace.hidden) loadMetadataExportScope();
});
metadataDbSelect.addEventListener("change", function() {
  if (packageExchangeState.active && !packageExchangeWorkspace.hidden) loadMetadataExportScope();
});
