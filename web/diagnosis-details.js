(function (root) {
  "use strict";

  var groups = [
    ["all_differences", "全部差异"],
    ["only_user_scope", "仅用户 SQL 纳入"],
    ["only_current_scope", "仅当前口径纳入"],
    ["user_only_numerator", "用户 SQL 计入分子"],
    ["current_only_numerator", "当前口径计入分子"]
  ];
  var fieldLabels = {
    admit_time: "办理住院时间",
    transfer_time: "转科时间",
    from_dept_id: "转出科室",
    from_ward_id: "转出病区",
    to_dept_id: "转入科室",
    to_ward_id: "转入病区",
    transfer_minutes: "转科耗时（分钟）"
  };
  var state = {
    comparisonId: "",
    group: "all_differences",
    page: 1,
    pageSize: 50,
    summary: null,
    trigger: null
  };

  function installMarkup() {
    var wrapper = document.createElement("div");
    wrapper.innerHTML =
      '<div id="diagnosisDetailOverlay" class="diagnosis-detail-overlay" hidden>' +
        '<section class="diagnosis-detail-dialog" role="dialog" aria-modal="true" aria-labelledby="diagnosisDetailTitle">' +
          '<header class="diagnosis-detail-header"><div><div class="diagnosis-detail-kicker">记录级计算对账</div>' +
            '<h2 id="diagnosisDetailTitle">两套 SQL 的差异明细</h2>' +
            '<p id="diagnosisDetailMeta"></p></div>' +
            '<button id="diagnosisDetailClose" class="diagnosis-detail-close" type="button" aria-label="关闭">×</button></header>' +
          '<section id="diagnosisSummaryGrid" class="diagnosis-summary-grid" aria-label="SQL 试运行结果"></section>' +
          '<nav id="diagnosisDetailTabs" class="diagnosis-detail-tabs" role="tablist" aria-label="差异类型"></nav>' +
          '<section class="diagnosis-detail-content">' +
            '<div id="diagnosisDetailNotice" class="diagnosis-detail-notice" role="status"></div>' +
            '<div id="diagnosisDetailTableWrap" class="diagnosis-detail-table-wrap"><table class="diagnosis-detail-table">' +
              '<thead id="diagnosisDetailHead"></thead><tbody id="diagnosisDetailBody"></tbody></table></div>' +
            '<div id="diagnosisDetailEmpty" class="diagnosis-detail-empty" hidden>本组没有差异记录</div>' +
          '</section>' +
          '<footer class="diagnosis-detail-footer"><div class="diagnosis-detail-paging">' +
            '<button id="diagnosisDetailPrev" type="button">上一页</button>' +
            '<span id="diagnosisDetailPageLabel">第 1 页</span>' +
            '<button id="diagnosisDetailNext" type="button">下一页</button>' +
            '<select id="diagnosisDetailPageSize" aria-label="每页条数"><option value="20">20条/页</option>' +
              '<option value="50" selected>50条/页</option><option value="100">100条/页</option></select></div>' +
            '<span class="diagnosis-detail-expiry">明细仅用于核对，24小时后自动清理</span></footer>' +
        '</section></div>';
    while (wrapper.firstChild) document.body.appendChild(wrapper.firstChild);
  }

  installMarkup();
  var overlay = document.getElementById("diagnosisDetailOverlay");
  var closeButton = document.getElementById("diagnosisDetailClose");
  var meta = document.getElementById("diagnosisDetailMeta");
  var summaryGrid = document.getElementById("diagnosisSummaryGrid");
  var tabs = document.getElementById("diagnosisDetailTabs");
  var notice = document.getElementById("diagnosisDetailNotice");
  var tableWrap = document.getElementById("diagnosisDetailTableWrap");
  var tableHead = document.getElementById("diagnosisDetailHead");
  var tableBody = document.getElementById("diagnosisDetailBody");
  var empty = document.getElementById("diagnosisDetailEmpty");
  var prev = document.getElementById("diagnosisDetailPrev");
  var next = document.getElementById("diagnosisDetailNext");
  var pageLabel = document.getElementById("diagnosisDetailPageLabel");
  var pageSize = document.getElementById("diagnosisDetailPageSize");

  function authToken() {
    return sessionStorage.getItem("hospitalAuthToken") || "";
  }

  async function requestJson(url) {
    var token = authToken();
    if (!token) {
      root.dispatchEvent(new CustomEvent("hospital-auth-required"));
      throw new Error("请使用医院人员账号登录后查看差异明细。");
    }
    var response = await fetch(url, {headers: {Authorization: "Bearer " + token}});
    var data = {};
    try { data = await response.json(); } catch (_) { data = {}; }
    if (response.status === 401) root.dispatchEvent(new CustomEvent("hospital-auth-required"));
    if (!response.ok) {
      var detail = data && data.detail;
      throw new Error((detail && detail.message) || detail || "差异明细读取失败");
    }
    return data;
  }

  function formatValue(value) {
    return value == null || value === "" ? "--" : String(value);
  }

  function metricCard(label, result) {
    var card = document.createElement("article");
    card.className = "diagnosis-summary-card";
    var heading = document.createElement("h3");
    heading.textContent = label;
    var formula = document.createElement("strong");
    formula.textContent = formatValue(result.numerator_count) + " / " +
      formatValue(result.denominator_count) + " = " + formatValue(result.result_value) + "%";
    var status = document.createElement("span");
    status.textContent = result.status === "success" ? "只读试运行成功" : formatValue(result.status);
    card.append(heading, formula, status);
    return card;
  }

  function renderSummary() {
    var summary = state.summary;
    meta.textContent = "数据来源：" + formatValue(summary.source_database) +
      " · 对账生成：" + formatValue(summary.created_at);
    summaryGrid.replaceChildren(
      metricCard("用户 SQL", summary.user_result || {}),
      metricCard("当前生效 SQL", summary.current_result || {})
    );
    tabs.replaceChildren();
    groups.forEach(function (definition) {
      var button = document.createElement("button");
      button.type = "button";
      button.className = "diagnosis-detail-tab" + (definition[0] === state.group ? " active" : "");
      button.dataset.group = definition[0];
      button.setAttribute("role", "tab");
      button.setAttribute("aria-selected", definition[0] === state.group ? "true" : "false");
      var label = document.createElement("span");
      label.textContent = definition[1];
      var count = document.createElement("strong");
      count.textContent = formatValue((summary.counts || {})[definition[0]]);
      button.append(label, count);
      tabs.appendChild(button);
    });
  }

  function appendCell(row, value, className) {
    var cell = document.createElement("td");
    cell.textContent = formatValue(value);
    if (className) cell.className = className;
    row.appendChild(cell);
  }

  function booleanText(value) {
    return value ? "是" : "否";
  }

  function detailColumns(items) {
    var keys = [];
    items.forEach(function (item) {
      Object.keys(item.current_details || {}).forEach(function (key) {
        if (keys.indexOf(key) < 0) keys.push(key);
      });
    });
    return keys;
  }

  function renderPage(data) {
    var items = data.items || [];
    var detailKeys = detailColumns(items);
    tableHead.replaceChildren();
    tableBody.replaceChildren();
    var header = document.createElement("tr");
    ["入院流水号", "差异原因", "用户 SQL 统计范围", "当前口径统计范围", "用户 SQL 分子", "当前口径分子"]
      .concat(detailKeys.map(function (key) { return fieldLabels[key] || key; }))
      .forEach(function (label) {
        var cell = document.createElement("th");
        cell.scope = "col";
        cell.textContent = label;
        header.appendChild(cell);
      });
    tableHead.appendChild(header);
    items.forEach(function (item) {
      var row = document.createElement("tr");
      appendCell(row, item.record_key, "diagnosis-record-key");
      appendCell(row, item.difference_reason, "diagnosis-reason");
      appendCell(row, booleanText(item.user_in_scope));
      appendCell(row, booleanText(item.current_in_scope));
      appendCell(row, booleanText(item.user_meets_numerator));
      appendCell(row, booleanText(item.current_meets_numerator));
      detailKeys.forEach(function (key) { appendCell(row, (item.current_details || {})[key]); });
      tableBody.appendChild(row);
    });
    notice.hidden = true;
    tableWrap.hidden = items.length === 0;
    empty.hidden = items.length !== 0;
    var total = Number(data.total || 0);
    var pageCount = Math.max(1, Math.ceil(total / state.pageSize));
    pageLabel.textContent = "第 " + state.page + " / " + pageCount + " 页，共 " + total + " 条";
    prev.disabled = state.page <= 1;
    next.disabled = state.page >= pageCount;
  }

  function setBusy(message) {
    notice.textContent = message;
    notice.hidden = false;
    notice.className = "diagnosis-detail-notice";
    tableWrap.hidden = true;
    empty.hidden = true;
    prev.disabled = true;
    next.disabled = true;
  }

  async function loadPage() {
    setBusy("正在读取差异记录...");
    try {
      var data = await requestJson(
        "/api/diagnosis-comparisons/" + encodeURIComponent(state.comparisonId) +
        "/details/" + state.group + "?page=" + state.page + "&page_size=" + state.pageSize
      );
      renderPage(data);
    } catch (error) {
      notice.textContent = error.message;
      notice.className = "diagnosis-detail-notice error";
      empty.textContent = "无法读取记录，请重新发起诊断后再试。";
      empty.hidden = false;
    }
  }

  async function open(comparisonId, trigger) {
    if (!/^CMP_[A-Za-z0-9_]+$/.test(comparisonId)) return;
    state.comparisonId = comparisonId;
    state.group = "all_differences";
    state.page = 1;
    state.trigger = trigger || document.activeElement;
    overlay.hidden = false;
    setBusy("正在读取两套 SQL 的对账结果...");
    closeButton.focus();
    try {
      state.summary = await requestJson(
        "/api/diagnosis-comparisons/" + encodeURIComponent(comparisonId)
      );
      renderSummary();
      await loadPage();
    } catch (error) {
      notice.textContent = error.message;
      notice.className = "diagnosis-detail-notice error";
    }
  }

  function close() {
    overlay.hidden = true;
    if (state.trigger && typeof state.trigger.focus === "function") state.trigger.focus();
  }

  document.addEventListener("click", function (event) {
    var trigger = event.target.closest && event.target.closest(".diagnosis-detail-trigger");
    if (trigger) {
      open(trigger.dataset.comparisonId || "", trigger);
      return;
    }
    var tab = event.target.closest && event.target.closest(".diagnosis-detail-tab");
    if (tab) {
      state.group = tab.dataset.group;
      state.page = 1;
      renderSummary();
      loadPage();
    }
  });
  closeButton.addEventListener("click", close);
  overlay.addEventListener("click", function (event) { if (event.target === overlay) close(); });
  document.addEventListener("keydown", function (event) {
    if (event.key === "Escape" && !overlay.hidden) close();
  });
  prev.addEventListener("click", function () { if (state.page > 1) { state.page -= 1; loadPage(); } });
  next.addEventListener("click", function () { state.page += 1; loadPage(); });
  pageSize.addEventListener("change", function () {
    state.pageSize = Number(pageSize.value);
    state.page = 1;
    loadPage();
  });

  root.DiagnosisDetails = {open: open, close: close};
}(window));
