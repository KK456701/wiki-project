"use strict";

(function() {
  var loaded = false;
  var refresh = document.getElementById("agentObservabilityRefresh");
  var startInput = document.getElementById("agentObservationStart");
  var endInput = document.getElementById("agentObservationEnd");
  var statusInput = document.getElementById("agentObservationStatus");
  var modelInput = document.getElementById("agentObservationModel");
  var toolInput = document.getElementById("agentObservationTool");
  var failureInput = document.getElementById("agentObservationFailure");

  function authHeaders() { return {Authorization: "Bearer " + hospitalAuthToken}; }
  function params() {
    var value = new URLSearchParams();
    if (startInput.value) value.set("started_after", startInput.value + " 00:00:00");
    if (endInput.value) value.set("started_before", endInput.value + " 23:59:59");
    if (statusInput.value) value.set("status", statusInput.value);
    if (modelInput.value) value.set("model_id", modelInput.value);
    if (toolInput.value.trim()) value.set("tool_name", toolInput.value.trim());
    if (failureInput.value) value.set("failure_class", failureInput.value);
    return value.toString();
  }
  function duration(ms) { return formatTraceDuration(Number(ms || 0)); }
  function percent(value) { return (Number(value || 0) * 100).toFixed(1) + "%"; }

  function renderMetrics(data) {
    var root = document.getElementById("agentObservationMetrics");
    var latency = data.latency_ms || {};
    var values = [
      ["请求量", data.request_count || 0],
      ["成功率", percent(data.success_rate)],
      ["未完成率", percent(data.incomplete_rate)],
      ["平均耗时", duration(latency.average)],
      ["p50", duration(latency.p50)],
      ["p95", duration(latency.p95)],
      ["p99", duration(latency.p99)],
      ["复合请求", data.compound_request_count || 0],
      ["复合平均", duration(data.compound_average_duration_ms)],
      ["重复调用停止率", percent(data.repeated_call_stop_rate)],
      ["Replan 率", percent(data.replan_rate)],
    ];
    root.innerHTML = "";
    values.forEach(function(item) {
      var box = document.createElement("div");
      box.className = "agent-observation-metric";
      var label = document.createElement("span"); label.textContent = item[0];
      var value = document.createElement("strong"); value.textContent = item[1];
      box.append(label, value); root.appendChild(box);
    });
  }

  function renderTrend(items) {
    var svg = document.getElementById("agentObservationTrend");
    var width = Math.max(640, svg.clientWidth || 900), height = 230, left = 50, right = 18, top = 16, bottom = 32;
    svg.setAttribute("viewBox", "0 0 " + width + " " + height);
    svg.innerHTML = "";
    if (!items.length) { var empty = document.createElementNS("http://www.w3.org/2000/svg", "text"); empty.setAttribute("x", 20); empty.setAttribute("y", 40); empty.setAttribute("class", "agent-observation-axis-label"); empty.textContent = "暂无趋势数据"; svg.appendChild(empty); return; }
    var maximum = Math.max.apply(null, items.map(function(item) { return Math.max(Number(item.planner_ms || 0), Number(item.final_answer_ms || 0)); })) || 1;
    for (var grid = 0; grid <= 4; grid++) {
      var y = top + (height - top - bottom) * grid / 4;
      var line = document.createElementNS("http://www.w3.org/2000/svg", "line");
      line.setAttribute("x1", left); line.setAttribute("x2", width - right); line.setAttribute("y1", y); line.setAttribute("y2", y); line.setAttribute("class", "agent-observation-grid"); svg.appendChild(line);
    }
    function point(item, index, key) {
      return [left + (width - left - right) * (items.length === 1 ? .5 : index / (items.length - 1)), top + (height - top - bottom) * (1 - Number(item[key] || 0) / maximum)];
    }
    [["planner_ms", "agent-observation-line-planner", "agent-observation-dot-planner"], ["final_answer_ms", "agent-observation-line-final", "agent-observation-dot-final"]].forEach(function(series) {
      var path = document.createElementNS("http://www.w3.org/2000/svg", "polyline");
      path.setAttribute("points", items.map(function(item, index) { return point(item, index, series[0]).join(","); }).join(" ")); path.setAttribute("class", series[1]); svg.appendChild(path);
      items.forEach(function(item, index) { var value = point(item, index, series[0]); var dot = document.createElementNS("http://www.w3.org/2000/svg", "circle"); dot.setAttribute("cx", value[0]); dot.setAttribute("cy", value[1]); dot.setAttribute("r", 3); dot.setAttribute("class", series[2]); svg.appendChild(dot); });
    });
    items.forEach(function(item, index) { var value = point(item, index, "planner_ms"); var label = document.createElementNS("http://www.w3.org/2000/svg", "text"); label.setAttribute("x", value[0]); label.setAttribute("y", height - 8); label.setAttribute("text-anchor", "middle"); label.setAttribute("class", "agent-observation-axis-label"); label.textContent = item.date.slice(5); svg.appendChild(label); });
  }

  function renderTable(targetId, columns, rows) {
    var root = document.getElementById(targetId); root.innerHTML = "";
    var table = document.createElement("table"); table.className = "agent-observation-table";
    var head = document.createElement("thead"), header = document.createElement("tr");
    columns.forEach(function(column) { var th = document.createElement("th"); th.textContent = column[0]; header.appendChild(th); }); head.appendChild(header); table.appendChild(head);
    var body = document.createElement("tbody");
    rows.forEach(function(row) { var tr = document.createElement("tr"); columns.forEach(function(column) { var td = document.createElement("td"); td.textContent = column[2] ? column[2](row[column[1]], row) : row[column[1]]; tr.appendChild(td); }); body.appendChild(tr); }); table.appendChild(body); root.appendChild(table);
  }

  function renderRuns(items) {
    var root = document.getElementById("agentObservationRuns"); root.innerHTML = "";
    items.forEach(function(item) {
      var row = document.createElement("div"); row.className = "agent-observation-run";
      [item.trace_id, item.started_at || "", item.final_status || "", duration(item.duration_ms)].forEach(function(text) { var span = document.createElement("span"); span.textContent = text; row.appendChild(span); });
      var button = document.createElement("button"); button.type = "button"; button.className = "ghost"; button.textContent = "查看链路"; button.addEventListener("click", function() { showTrace(item.trace_id); }); row.appendChild(button); root.appendChild(row);
    });
  }

  async function load() {
    refresh.disabled = true;
    try {
      var query = params();
      var results = await Promise.all([
        fetch("/api/agent/runs/metrics?" + query, {headers: authHeaders()}),
        fetch("/api/agent/runs?" + query, {headers: authHeaders()}),
        fetch("/api/agent/capabilities", {headers: authHeaders()}),
      ]);
      var payloads = await Promise.all(results.map(function(response) { return response.json(); }));
      if (!results[0].ok || !results[1].ok) throw new Error(payloads[0].detail || payloads[1].detail || "运行观察加载失败");
      var metrics = payloads[0], runs = payloads[1].items || [];
      renderMetrics(metrics); renderTrend(metrics.trend || []); renderRuns(runs);
      renderTable("agentObservationTools", [["工具", "tool_name"], ["调用", "calls"], ["失败", "failures"], ["失败率", "failures", function(value, row) { return percent(row.calls ? value / row.calls : 0); }], ["总耗时", "duration_ms", duration]], metrics.tools || []);
      renderTable("agentObservationModels", [["模型", "model_id"], ["调用", "calls"], ["超时", "timeouts"], ["输入 / 输出 Token", "input_tokens", function(value, row) { return value + " / " + row.output_tokens; }], ["总耗时", "duration_ms", duration]], metrics.models || []);
      var warningRoot = document.getElementById("agentObservationWarnings"); warningRoot.hidden = !(metrics.warnings || []).length; warningRoot.textContent = (metrics.warnings || []).map(function(item) { return item.message; }).join("；");
      if (!loaded) {
        (payloads[2].models || []).forEach(function(model) { var option = document.createElement("option"); option.value = model.id; option.textContent = model.name; modelInput.appendChild(option); }); loaded = true;
      }
    } catch (error) {
      var warningRoot = document.getElementById("agentObservationWarnings"); warningRoot.hidden = false; warningRoot.textContent = error.message || error;
    } finally { refresh.disabled = false; }
  }

  [startInput, endInput, statusInput, modelInput, failureInput].forEach(function(element) { element.addEventListener("change", load); });
  toolInput.addEventListener("keydown", function(event) { if (event.key === "Enter") load(); });
  refresh.addEventListener("click", load);
  window.activateAgentObservabilityPage = load;
})();
