(function (root, factory) {
  var api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  if (root) root.AgentRuntimeUI = api;
})(typeof window !== "undefined" ? window : globalThis, function () {
  "use strict";

  var capabilities = {enabled: false, mode: "legacy"};

  function hasPermission(user, permission) {
    return !!(user && (user.permissions || []).indexOf(permission) >= 0);
  }

  function selectMode(caps, user) {
    return caps && caps.enabled === true && caps.mode === "tool_calling" &&
      user && user.role === "hospital" &&
      hasPermission(user, "indicator_detail_export")
      ? "tool_calling" : "legacy";
  }

  function projectEvent(source) {
    var event = {
      event: String(source && source.event || "agent_error"),
      trace_id: String(source && source.trace_id || "")
    };
    ["tool_name", "status", "code", "message", "stop_reason"].forEach(function (key) {
      if (source && source[key] !== undefined) event[key] = String(source[key]);
    });
    if (source && source.step !== undefined) event.step = Number(source.step || 0);
    if (source && source.step_count !== undefined) event.step_count = Number(source.step_count || 0);
    if (source && source.reused !== undefined) event.reused = source.reused === true;
    return event;
  }

  function canFallbackToLegacy(status, agentStarted) {
    return Number(status) === 503 && agentStarted !== true;
  }

  function modeText(mode) {
    if (mode === "tool_calling") return "工具协作模式";
    if (capabilities.mode === "shadow") return "旧流程 · 只读评估中";
    return "稳定流程";
  }

  function modelSelectorOptions(caps) {
    var models = caps && Array.isArray(caps.models) ? caps.models : [];
    var current = String(caps && caps.model || "");
    return models.map(function (model) {
      var id = String(model && model.id || "");
      return {
        value: id,
        label: String(model && model.name || id || "未命名模型"),
        selected: id === current
      };
    });
  }

  function renderModelSelector(element, caps) {
    if (!element) return;
    var options = modelSelectorOptions(caps);
    element.innerHTML = "";
    if (!options.length) {
      element.hidden = true;
      return;
    }
    options.forEach(function (item) {
      var option = document.createElement("option");
      option.value = item.value;
      option.textContent = item.label;
      option.selected = item.selected;
      element.appendChild(option);
    });
    element.hidden = false;
  }

  function buildChatPayload(query, sessionId, modelId) {
    var payload = {query: query, session_id: sessionId};
    if (modelId) payload.model_id = modelId;
    return payload;
  }

  function renderModeBadge(element, user) {
    if (!element) return;
    var mode = selectMode(capabilities, user);
    element.textContent = modeText(mode);
    element.dataset.mode = mode;
    element.title = mode === "tool_calling"
      ? "模型会按问题选择受控工具，正式审批和发布仍需人工完成。"
      : "当前继续使用原有稳定流程。";
  }

  async function refreshCapabilities(options) {
    var token = options && options.token || "";
    var user = options && options.user || null;
    if (!token || !user || user.role !== "hospital") {
      capabilities = {enabled: false, mode: "legacy"};
      renderModeBadge(options && options.element, user);
      return capabilities;
    }
    try {
      var response = await fetch("/api/agent/capabilities", {
        headers: {Authorization: "Bearer " + token}
      });
      if (!response.ok) throw new Error("capabilities unavailable");
      capabilities = await response.json();
    } catch (_) {
      capabilities = {enabled: false, mode: "legacy"};
    }
    renderModeBadge(options && options.element, user);
    renderModelSelector(options && options.modelSelector, capabilities);
    return capabilities;
  }

  function currentMode(user) {
    return selectMode(capabilities, user);
  }

  function parseBlock(block) {
    var name = "message";
    var data = "";
    block.split("\n").forEach(function (line) {
      if (line.indexOf("event: ") === 0) name = line.slice(7).trim();
      if (line.indexOf("data: ") === 0) data += line.slice(6);
    });
    if (!data) return null;
    try {
      var payload = JSON.parse(data);
      payload.event = name;
      return projectEvent(payload);
    } catch (_) {
      return null;
    }
  }

  async function streamAgent(options) {
    var started = false;
    var response = await fetch("/api/agent/chat/stream", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: "Bearer " + options.token
      },
      body: JSON.stringify(buildChatPayload(
        options.query,
        options.sessionId,
        options.modelId
      ))
    });
    if (!response.ok) {
      var error = new Error("工具协作模式暂不可用，请切回稳定流程后重试。");
      error.status = response.status;
      error.agentStarted = false;
      throw error;
    }
    if (!response.body) throw new Error("浏览器不支持流式读取");
    var reader = response.body.getReader();
    var decoder = new TextDecoder("utf-8");
    var buffer = "";
    while (true) {
      var chunk = await reader.read();
      if (chunk.done) break;
      buffer += decoder.decode(chunk.value, {stream: true});
      var blocks = buffer.split("\n\n");
      buffer = blocks.pop() || "";
      blocks.forEach(function (block) {
        var event = parseBlock(block);
        if (!event) return;
        if (event.event === "agent_start") started = true;
        options.onEvent(event);
      });
    }
    return {agentStarted: started};
  }

  var toolLabels = {
    search_indicator_rules: "搜索相关指标",
    get_effective_rule: "读取本院生效口径",
    inspect_indicator_implementation: "检查字段与实施状态",
    prepare_indicator_sql: "生成并校验受控 SQL",
    trial_run_indicator_sql: "执行只读试运行",
    diagnose_indicator_issue: "分析指标异常",
    create_indicator_draft: "生成指标工作草稿",
    preview_rule_change: "预览本院口径变化"
  };

  function createEvidenceTrack(bubble) {
    var track = document.createElement("ol");
    track.className = "agent-evidence-track";
    track.setAttribute("aria-label", "本次回答的证据轨迹");
    track.tabIndex = 0;
    var body = bubble.querySelector(".message-body");
    bubble.insertBefore(track, body || null);
    return track;
  }

  function appendEvidenceEvent(track, event) {
    if (!track || !event || ["tool_call", "tool_result"].indexOf(event.event) < 0) return;
    var selector = '[data-tool-name="' + String(event.tool_name || "").replace(/[^a-z0-9_]/g, "") + '"]';
    var item = event.event === "tool_result" ? track.querySelector(selector + ":last-child") : null;
    if (!item) {
      item = document.createElement("li");
      item.dataset.toolName = event.tool_name || "unknown";
      var label = document.createElement("span");
      label.textContent = toolLabels[event.tool_name] || "处理业务信息";
      var state = document.createElement("small");
      state.textContent = "进行中";
      item.append(label, state);
      track.appendChild(item);
    }
    if (event.event === "tool_result") {
      item.classList.add(event.status === "success" || event.status === "preview_ready" ? "is-done" : "is-warning");
      item.querySelector("small").textContent = event.reused
        ? "复用本轮已有结果"
        : event.message || event.code || "已完成";
    }
  }

  return {
    selectMode: selectMode,
    modelSelectorOptions: modelSelectorOptions,
    buildChatPayload: buildChatPayload,
    projectEvent: projectEvent,
    canFallbackToLegacy: canFallbackToLegacy,
    refreshCapabilities: refreshCapabilities,
    currentMode: currentMode,
    streamAgent: streamAgent,
    createEvidenceTrack: createEvidenceTrack,
    appendEvidenceEvent: appendEvidenceEvent
  };
});
