(function (root) {
  "use strict";

  var state = {
    token: sessionStorage.getItem("hospitalAuthToken") || "",
    user: readStoredUser(),
    runId: "",
    group: "denominator",
    page: 1,
    pageSize: 50,
    summary: null,
    trigger: null,
    passwordResolver: null,
    passwordRejecter: null,
    currentPassword: ""
  };

  function readStoredUser() {
    try { return JSON.parse(sessionStorage.getItem("currentUser") || "null"); }
    catch (_) { return null; }
  }

  function installMarkup() {
    var wrapper = document.createElement("div");
    wrapper.innerHTML =
      '<div id="indicatorDetailOverlay" class="indicator-detail-overlay" hidden>' +
        '<section class="indicator-detail-dialog" role="dialog" aria-modal="true" aria-labelledby="indicatorDetailTitle">' +
          '<header class="indicator-detail-header"><div><div class="indicator-detail-kicker">本次计算依据</div>' +
            '<h2 id="indicatorDetailTitle">指标明细核对</h2><div id="indicatorDetailMeta" class="indicator-detail-meta"></div></div>' +
            '<button id="indicatorDetailClose" class="indicator-detail-close" type="button" aria-label="关闭">×</button></header>' +
          '<section id="indicatorDetailSource" class="indicator-detail-source" aria-label="数据来源">' +
            '<div id="indicatorDetailSourceSummary" class="indicator-detail-source-summary"></div>' +
            '<details id="indicatorDetailLineage" class="indicator-detail-lineage"><summary>查看字段来源</summary>' +
              '<dl id="indicatorDetailLineageList"></dl></details></section>' +
          '<div id="indicatorDetailTabs" class="indicator-detail-tabs" role="tablist"></div>' +
          '<div class="indicator-detail-content"><div id="indicatorDetailNotice" class="indicator-detail-notice" role="status">正在读取本次计算明细</div>' +
            '<div id="indicatorDetailTableWrap" class="detail-table-scroll"><table class="indicator-detail-table">' +
              '<thead id="indicatorDetailTableHead"></thead><tbody id="indicatorDetailTableBody"></tbody></table></div>' +
            '<div id="indicatorDetailEmpty" class="indicator-detail-empty" hidden>本组没有记录</div></div>' +
          '<footer class="indicator-detail-footer"><div class="indicator-detail-paging">' +
            '<button id="indicatorDetailPrev" type="button">上一页</button><span id="indicatorDetailPageLabel">第 1 页</span>' +
            '<button id="indicatorDetailNext" type="button">下一页</button>' +
            '<select id="indicatorDetailPageSize" aria-label="每页条数"><option value="20">20条/页</option><option value="50" selected>50条/页</option><option value="100">100条/页</option></select></div>' +
            '<div class="indicator-detail-actions"><span id="indicatorDetailExpiry" class="indicator-detail-expiry"></span>' +
              '<button id="indicatorDetailExport" class="indicator-detail-export" type="button">生成并下载 Excel</button></div></footer>' +
        '</section></div>' +
      '<div id="hospitalPasswordOverlay" class="hospital-password-overlay" hidden>' +
        '<form id="hospitalPasswordForm" class="hospital-password-dialog"><h2>首次登录，请设置新密码</h2>' +
          '<p>新密码至少8位，并且同时包含字母和数字。修改完成后才能查看或导出指标明细。</p>' +
          '<label for="hospitalNewPassword">新密码</label><input id="hospitalNewPassword" type="password" autocomplete="new-password" required />' +
          '<label for="hospitalNewPasswordConfirm">再次输入新密码</label><input id="hospitalNewPasswordConfirm" type="password" autocomplete="new-password" required />' +
          '<div id="hospitalPasswordError" class="hospital-password-error" hidden></div>' +
          '<button id="hospitalPasswordSubmit" type="submit">保存新密码并进入系统</button></form></div>';
    while (wrapper.firstChild) document.body.appendChild(wrapper.firstChild);
  }

  installMarkup();

  var overlay = document.getElementById("indicatorDetailOverlay");
  var title = document.getElementById("indicatorDetailTitle");
  var meta = document.getElementById("indicatorDetailMeta");
  var sourceSummary = document.getElementById("indicatorDetailSourceSummary");
  var lineageDetails = document.getElementById("indicatorDetailLineage");
  var lineageList = document.getElementById("indicatorDetailLineageList");
  var tabs = document.getElementById("indicatorDetailTabs");
  var notice = document.getElementById("indicatorDetailNotice");
  var tableWrap = document.getElementById("indicatorDetailTableWrap");
  var tableHead = document.getElementById("indicatorDetailTableHead");
  var tableBody = document.getElementById("indicatorDetailTableBody");
  var empty = document.getElementById("indicatorDetailEmpty");
  var prev = document.getElementById("indicatorDetailPrev");
  var next = document.getElementById("indicatorDetailNext");
  var pageLabel = document.getElementById("indicatorDetailPageLabel");
  var pageSize = document.getElementById("indicatorDetailPageSize");
  var exportButton = document.getElementById("indicatorDetailExport");
  var expiry = document.getElementById("indicatorDetailExpiry");
  var passwordOverlay = document.getElementById("hospitalPasswordOverlay");
  var passwordForm = document.getElementById("hospitalPasswordForm");
  var newPassword = document.getElementById("hospitalNewPassword");
  var confirmPassword = document.getElementById("hospitalNewPasswordConfirm");
  var passwordError = document.getElementById("hospitalPasswordError");
  var passwordSubmit = document.getElementById("hospitalPasswordSubmit");

  function setAuth(payload) {
    if (payload && payload.token) state.token = payload.token;
    if (payload && payload.user) state.user = payload.user;
    if (state.token) sessionStorage.setItem("hospitalAuthToken", state.token);
    else sessionStorage.removeItem("hospitalAuthToken");
    if (state.user) sessionStorage.setItem("currentUser", JSON.stringify(state.user));
  }

  function clearAuth() {
    state.token = "";
    if (state.user && state.user.role === "hospital") state.user = null;
    sessionStorage.removeItem("hospitalAuthToken");
    sessionStorage.removeItem("currentUser");
  }

  function userFromLogin(data) {
    return {
      role: "hospital",
      accountId: data.account_id,
      hospitalId: data.hospital_id,
      userId: data.user_id,
      permissions: data.permissions || [],
      mustChangePassword: !!data.must_change_password,
      loginTime: new Date().toISOString()
    };
  }

  function apiMessage(data, fallback) {
    var detail = data && data.detail;
    if (detail && typeof detail === "object") return detail.message || fallback;
    return detail || fallback;
  }

  async function detailFetch(url, options) {
    var request = options || {};
    var headers = Object.assign({}, request.headers || {}, {Authorization: "Bearer " + state.token});
    var response = await fetch(url, Object.assign({}, request, {headers: headers}));
    if (response.status === 401) {
      clearAuth();
      root.dispatchEvent(new CustomEvent("hospital-auth-required"));
    }
    return response;
  }

  async function requestJson(url, options) {
    var response = await detailFetch(url, options);
    var data = null;
    try { data = await response.json(); } catch (_) { data = {}; }
    if (!response.ok) {
      var error = new Error(apiMessage(data, "请求失败，请稍后重试"));
      error.status = response.status;
      throw error;
    }
    return data;
  }

  function hasPermission(code) {
    return !!(state.user && (state.user.permissions || []).indexOf(code) >= 0);
  }

  function showNotice(message, kind) {
    notice.textContent = message;
    notice.className = "indicator-detail-notice" + (kind ? " " + kind : "");
    notice.hidden = false;
  }

  function setBusy(message) {
    showNotice(message || "正在读取本次计算明细", "");
    tableWrap.hidden = true;
    empty.hidden = true;
    prev.disabled = true;
    next.disabled = true;
  }

  function formatTime(value) {
    return String(value || "").replace("T", " ").slice(0, 19);
  }

  function sourceItem(label, value) {
    var item = document.createElement("span");
    var strong = document.createElement("strong");
    strong.textContent = label + "：";
    var text = document.createElement("span");
    text.textContent = value;
    item.append(strong, text);
    return item;
  }

  function renderSource(summary) {
    sourceSummary.replaceChildren(
      sourceItem("来源数据库", summary.source_database || "未记录"),
      sourceItem("取数表", (summary.source_tables || []).join("、") || "未记录")
    );
    lineageList.replaceChildren();
    var fieldLineage = summary.field_lineage || [];
    fieldLineage.forEach(function (lineage) {
      var term = document.createElement("dt");
      term.textContent = lineage.label;
      var detail = document.createElement("dd");
      detail.textContent = lineage.explanation;
      lineageList.append(term, detail);
    });
    lineageDetails.hidden = fieldLineage.length === 0;
    lineageDetails.open = false;
  }

  function renderSummary() {
    var summary = state.summary;
    title.textContent = summary.rule_name || "指标明细核对";
    meta.replaceChildren();
    [
      summary.effective_level === "hospital" ? "本院生效口径 v" + (summary.hospital_version || "-") : "标准口径 v" + (summary.national_version || "-"),
      "统计区间：" + formatTime(summary.stat_start) + " 至 " + formatTime(summary.stat_end) + "（不含结束时刻）",
      "明细生成：" + formatTime(summary.created_at)
    ].forEach(function (text) {
      var span = document.createElement("span");
      span.textContent = text;
      meta.appendChild(span);
    });
    renderSource(summary);
    var definitions = [
      ["denominator", "统计范围", summary.denominator_count, "本次纳入计算的全部记录"],
      ["numerator", "达到要求", summary.numerator_count, "符合本院口径的记录"],
      ["unmatched", "未达到要求", summary.unmatched_count, "纳入统计但未达到要求"]
    ];
    tabs.replaceChildren();
    definitions.forEach(function (definition) {
      var button = document.createElement("button");
      button.type = "button";
      button.className = "indicator-detail-tab" + (definition[0] === state.group ? " active" : "");
      button.dataset.group = definition[0];
      button.setAttribute("role", "tab");
      button.setAttribute("aria-selected", definition[0] === state.group ? "true" : "false");
      var strong = document.createElement("strong");
      strong.textContent = definition[1] + " " + definition[2] + "条";
      var small = document.createElement("span");
      small.textContent = definition[3];
      button.append(strong, small);
      tabs.appendChild(button);
    });
    expiry.textContent = "完整文件仅限授权使用，生成后24小时自动清理";
    exportButton.disabled = !hasPermission("indicator_detail_export");
    exportButton.title = exportButton.disabled ? "当前账号没有指标明细导出权限" : "";
  }

  function renderPage(data) {
    tableHead.replaceChildren();
    tableBody.replaceChildren();
    var items = data.items || [];
    empty.textContent = "本组没有记录";
    var columns = items.length
      ? Object.keys(items[0])
      : (state.summary.columns || []).map(function (column) { return column.label; }).concat(["是否达到要求"]);
    var headerRow = document.createElement("tr");
    columns.forEach(function (label) {
      var cell = document.createElement("th");
      cell.scope = "col";
      cell.textContent = label;
      headerRow.appendChild(cell);
    });
    tableHead.appendChild(headerRow);
    items.forEach(function (item) {
      var row = document.createElement("tr");
      columns.forEach(function (label) {
        var rowCell = document.createElement("td");
        rowCell.textContent = item[label] == null ? "-" : String(item[label]);
        row.appendChild(rowCell);
      });
      tableBody.appendChild(row);
    });
    notice.hidden = true;
    empty.hidden = items.length !== 0;
    tableWrap.hidden = items.length === 0;
    var pageCount = Math.max(1, Math.ceil(Number(data.total || 0) / state.pageSize));
    pageLabel.textContent = "第 " + state.page + " / " + pageCount + " 页，共 " + data.total + " 条";
    prev.disabled = state.page <= 1;
    next.disabled = state.page >= pageCount;
  }

  async function loadPage() {
    setBusy("正在读取本次计算明细");
    try {
      var data = await requestJson(
        "/api/sql-runs/" + encodeURIComponent(state.runId) + "/details/" + state.group +
        "?page=" + state.page + "&page_size=" + state.pageSize
      );
      renderPage(data);
    } catch (error) {
      showNotice(error.message, "error");
      empty.textContent = error.status === 410 ? "明细已过期，请关闭后重新试运行。" : "读取失败，请稍后重试。";
      empty.hidden = false;
    }
  }

  async function open(runId, group, trigger) {
    if (!/^RUN_[A-Za-z0-9_]+$/.test(runId) || !/^(denominator|numerator|unmatched)$/.test(group)) return;
    state.runId = runId;
    state.group = group;
    state.page = 1;
    state.trigger = trigger || document.activeElement;
    overlay.hidden = false;
    setBusy("正在读取本次计算明细");
    document.getElementById("indicatorDetailClose").focus();
    if (!state.token) {
      showNotice("请使用医院人员账号登录后查看指标明细。", "error");
      root.dispatchEvent(new CustomEvent("hospital-auth-required"));
      return;
    }
    try {
      state.summary = await requestJson(
        "/api/sql-runs/" + encodeURIComponent(runId) + "/details",
        {method: "POST"}
      );
      renderSummary();
      await loadPage();
    } catch (error) {
      showNotice(error.message, "error");
    }
  }

  function close() {
    overlay.hidden = true;
    if (state.trigger && typeof state.trigger.focus === "function") state.trigger.focus();
  }

  async function downloadExport(result) {
    var response = await detailFetch(
      "/api/indicator-exports/" + encodeURIComponent(result.export_id) + "/download"
    );
    if (!response.ok) {
      var data = await response.json();
      throw new Error(apiMessage(data, "文件下载失败"));
    }
    var blob = await response.blob();
    var url = URL.createObjectURL(blob);
    var link = document.createElement("a");
    link.href = url;
    link.download = result.file_name;
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
  }

  async function exportExcel() {
    if (!hasPermission("indicator_detail_export")) return;
    var confirmed = root.confirm(
      "文件含完整患者级明细，仅限已授权的医疗质量核对使用。\n\n文件会在服务器临时保存，24小时后自动删除。确认继续生成并下载吗？"
    );
    if (!confirmed) return;
    exportButton.disabled = true;
    exportButton.textContent = "正在生成...";
    try {
      var result = await requestJson(
        "/api/sql-runs/" + encodeURIComponent(state.runId) + "/exports",
        {method: "POST", headers: {"Content-Type": "application/json"}, body: JSON.stringify({confirmed: true})}
      );
      await downloadExport(result);
      showNotice("Excel 已生成并开始下载，服务器临时文件将在24小时后自动清理。", "success");
    } catch (error) {
      showNotice(error.message, "error");
    } finally {
      exportButton.disabled = !hasPermission("indicator_detail_export");
      exportButton.textContent = "生成并下载 Excel";
    }
  }

  async function exportUploadComparison(runId, fileToken, trigger) {
    if (!/^RUN_[A-Za-z0-9_]+$/.test(runId) || !/^[A-Za-z0-9_-]+$/.test(fileToken)) return;
    if (!state.token) {
      root.dispatchEvent(new CustomEvent("hospital-auth-required"));
      return;
    }
    if (!hasPermission("indicator_detail_export")) {
      root.alert("当前账号没有指标明细导出权限，请联系管理员。");
      return;
    }
    var confirmed = root.confirm(
      "将导出上传文件与本次系统试运行的汇总差异。\n\n当前上传文件只有汇总值，文件会列出分子、分母和指标率的一致项与不一致项，不代表患者级记录交集。确认继续吗？"
    );
    if (!confirmed) return;
    var originalText = trigger.textContent;
    trigger.disabled = true;
    trigger.textContent = "正在生成差异表...";
    try {
      var result = await requestJson(
        "/api/sql-runs/" + encodeURIComponent(runId) + "/upload-comparison-exports",
        {
          method: "POST",
          headers: {"Content-Type": "application/json"},
          body: JSON.stringify({confirmed: true, file_token: fileToken})
        }
      );
      await downloadExport(result);
      trigger.textContent = "差异表已开始下载";
    } catch (error) {
      root.alert(error.message);
      trigger.textContent = originalText;
    } finally {
      trigger.disabled = false;
    }
  }

  function completeHospitalLogin(data, currentPassword) {
    state.token = data.token;
    state.user = userFromLogin(data);
    setAuth({token: state.token, user: state.user});
    if (!data.must_change_password) return Promise.resolve({token: state.token, user: state.user});
    state.currentPassword = currentPassword;
    passwordOverlay.hidden = false;
    passwordError.hidden = true;
    newPassword.value = "";
    confirmPassword.value = "";
    setTimeout(function () { newPassword.focus(); }, 0);
    return new Promise(function (resolve, reject) {
      state.passwordResolver = resolve;
      state.passwordRejecter = reject;
    });
  }

  passwordForm.addEventListener("submit", async function (event) {
    event.preventDefault();
    passwordError.hidden = true;
    if (newPassword.value !== confirmPassword.value) {
      passwordError.textContent = "两次输入的新密码不一致";
      passwordError.hidden = false;
      return;
    }
    passwordSubmit.disabled = true;
    try {
      var response = await detailFetch("/api/auth/hospital/change-password", {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({current_password: state.currentPassword, new_password: newPassword.value})
      });
      var data = await response.json();
      if (!response.ok) {
        var changeError = new Error(apiMessage(data, "密码修改失败"));
        changeError.status = response.status;
        throw changeError;
      }
      state.token = data.token;
      state.user = userFromLogin(data);
      setAuth({token: state.token, user: state.user});
      state.currentPassword = "";
      passwordOverlay.hidden = true;
      if (state.passwordResolver) state.passwordResolver({token: state.token, user: state.user});
      state.passwordResolver = null;
      state.passwordRejecter = null;
    } catch (error) {
      if (error.status === 401) {
        passwordOverlay.hidden = true;
        state.currentPassword = "";
        if (state.passwordRejecter) state.passwordRejecter(error);
        state.passwordResolver = null;
        state.passwordRejecter = null;
        return;
      }
      passwordError.textContent = error.message;
      passwordError.hidden = false;
    } finally {
      passwordSubmit.disabled = false;
    }
  });

  async function logout() {
    if (state.token) {
      try { await detailFetch("/api/auth/hospital/logout", {method: "POST"}); }
      catch (_) { /* Local logout still proceeds. */ }
    }
    clearAuth();
  }

  document.addEventListener("click", function (event) {
    var comparisonTrigger = event.target.closest(".upload-comparison-export-trigger");
    if (comparisonTrigger) {
      exportUploadComparison(
        comparisonTrigger.dataset.runId || "",
        comparisonTrigger.dataset.fileToken || "",
        comparisonTrigger
      );
      return;
    }
    var trigger = event.target.closest(".indicator-detail-trigger");
    if (trigger) open(trigger.dataset.runId || "", trigger.dataset.detailGroup || "", trigger);
    var tab = event.target.closest(".indicator-detail-tab");
    if (tab && tab.dataset.group !== state.group) {
      state.group = tab.dataset.group;
      state.page = 1;
      renderSummary();
      loadPage();
    }
  });
  document.getElementById("indicatorDetailClose").addEventListener("click", close);
  prev.addEventListener("click", function () { if (state.page > 1) { state.page -= 1; loadPage(); } });
  next.addEventListener("click", function () { state.page += 1; loadPage(); });
  pageSize.addEventListener("change", function () { state.pageSize = Number(pageSize.value); state.page = 1; loadPage(); });
  exportButton.addEventListener("click", exportExcel);
  document.addEventListener("keydown", function (event) {
    if (event.key === "Escape" && !overlay.hidden && passwordOverlay.hidden) close();
    if (event.key !== "Tab") return;
    var container = !passwordOverlay.hidden
      ? passwordOverlay
      : (!overlay.hidden ? overlay : null);
    if (!container) return;
    var focusable = Array.from(
      container.querySelectorAll("button:not([disabled]), input:not([disabled]), select:not([disabled])")
    );
    if (!focusable.length) return;
    var first = focusable[0];
    var last = focusable[focusable.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  });

  root.IndicatorDetails = {
    open: open,
    close: close,
    setAuth: setAuth,
    clearAuth: clearAuth,
    completeHospitalLogin: completeHospitalLogin,
    logout: logout,
    detailFetch: detailFetch,
    currentUser: function () { return state.user; },
    token: function () { return state.token; }
  };
}(window));
