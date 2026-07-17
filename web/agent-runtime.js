(function (root, factory) {
  var api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  if (root) root.AgentRuntimeUI = api;
})(typeof window !== "undefined" ? window : globalThis, function () {
  "use strict";

  var capabilities = {enabled: false};

  function projectEvent(source) {
    var event = {
      event: String(source && source.event || "agent_error"),
      trace_id: String(source && source.trace_id || "")
    };
    ["tool_name", "status", "code", "message", "stop_reason", "fallback_category", "failure_code"].forEach(function (key) {
      if (source && source[key] !== undefined) event[key] = String(source[key]);
    });
    if (source && source.step !== undefined) event.step = Number(source.step || 0);
    if (source && source.step_count !== undefined) event.step_count = Number(source.step_count || 0);
    if (source && source.reused !== undefined) event.reused = source.reused === true;
    return event;
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
    var currentValue = element.value;
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
      option.selected = currentValue ? item.value === currentValue : item.selected;
      element.appendChild(option);
    });
    element.hidden = false;
  }

  function buildChatPayload(query, sessionId, modelId, fileKey) {
    var payload = {query: query, session_id: sessionId};
    if (modelId) payload.model_id = modelId;
    if (fileKey) payload.file_key = fileKey;
    return payload;
  }

  function agentRequestErrorMessage(status) {
    if (Number(status) === 401) return "登录状态已失效，请重新登录。";
    return "Agent 请求失败，请稍后重试或联系系统管理员。";
  }

  async function refreshCapabilities(options) {
    var token = options && options.token || "";
    var user = options && options.user || null;
    if (!token || !user || user.role !== "hospital") {
      capabilities = {enabled: false};
      renderModelSelector(options && options.modelSelector, capabilities);
      return capabilities;
    }
    try {
      var response = await fetch("/api/agent/capabilities", {
        headers: {Authorization: "Bearer " + token}
      });
      if (!response.ok) throw new Error("capabilities unavailable");
      capabilities = await response.json();
    } catch (_) {
      capabilities = {enabled: false};
    }
    renderModelSelector(options && options.modelSelector, capabilities);
    return capabilities;
  }

  function isAvailable() {
    return capabilities.enabled === true;
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
    if (!options.token) throw new Error("请先登录医院账号。");
    if (!isAvailable()) throw new Error("Agent 当前不可用，请联系系统管理员。");
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
        options.modelId,
        options.fileKey
      ))
    });
    if (!response.ok) {
      var error = new Error(agentRequestErrorMessage(response.status));
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
    preview_rule_change: "预览本院口径变化",
    analyze_uploaded_indicators: "分析上传的指标文件"
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
    modelSelectorOptions: modelSelectorOptions,
    buildChatPayload: buildChatPayload,
    agentRequestErrorMessage: agentRequestErrorMessage,
    projectEvent: projectEvent,
    refreshCapabilities: refreshCapabilities,
    isAvailable: isAvailable,
    streamAgent: streamAgent,
    createEvidenceTrack: createEvidenceTrack,
    appendEvidenceEvent: appendEvidenceEvent
  };
});
