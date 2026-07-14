#!/usr/bin/env node

import { spawn } from "node:child_process";
import readline from "node:readline";


const REQUIRED_TOOLS = ["model_analyze", "model_query_class_id", "table_analysis"];


function parseArgs(argv) {
  const result = { mode: "basic", timeout: 10000 };
  for (let index = 0; index < argv.length; index += 2) {
    const key = argv[index]?.replace(/^--/, "");
    const value = argv[index + 1];
    if (!key || value === undefined) {
      throw new Error("参数必须使用 --名称 值 的形式。");
    }
    result[key] = value;
  }
  result.timeout = Number(result.timeout);
  if (!Number.isFinite(result.timeout) || result.timeout <= 0) {
    throw new Error("--timeout 必须是正数。");
  }
  return result;
}


function extractJsonContent(result) {
  const text = result?.content?.find((item) => item?.type === "text")?.text;
  if (!text) return {};
  try {
    return JSON.parse(text);
  } catch {
    return {};
  }
}


function countLineage(fields) {
  if (!Array.isArray(fields)) return 0;
  return fields.reduce(
    (total, field) => total
      + (Array.isArray(field.relations) ? field.relations.length : 0)
      + (Array.isArray(field.references) ? field.references.length : 0),
    0,
  );
}


async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (!args.entrypoint) throw new Error("未提供 --entrypoint。");
  if (!["basic", "platform"].includes(args.mode)) {
    throw new Error("--mode 只能是 basic 或 platform。");
  }
  if (args.mode === "platform" && (!args.project || !args.table)) {
    throw new Error("平台检测必须提供 --project 和 --table。");
  }
  if (args.mode === "platform" && !process.env.WXP_TENANTSESSION) {
    throw new Error("平台检测需要本机 WXP_TENANTSESSION。");
  }

  const child = spawn(process.execPath, [args.entrypoint], {
    env: process.env,
    stdio: ["pipe", "pipe", "pipe"],
    windowsHide: true,
  });
  const pending = new Map();
  let nextId = 1;

  child.stderr.resume();
  const lines = readline.createInterface({ input: child.stdout });
  lines.on("line", (line) => {
    let message;
    try {
      message = JSON.parse(line);
    } catch {
      return;
    }
    if (message.id !== undefined && pending.has(message.id)) {
      const waiter = pending.get(message.id);
      pending.delete(message.id);
      if (message.error) {
        waiter.reject(new Error(message.error.message || "MCP 调用失败"));
      } else {
        waiter.resolve(message.result);
      }
    }
  });
  child.on("exit", (code) => {
    if (!pending.size) return;
    const error = new Error(`MCP 进程提前退出，退出码：${code ?? "未知"}`);
    for (const waiter of pending.values()) waiter.reject(error);
    pending.clear();
  });

  function request(method, params = {}) {
    const id = nextId++;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        pending.delete(id);
        reject(new Error(`${method} 超时`));
      }, args.timeout);
      pending.set(id, {
        resolve: (value) => {
          clearTimeout(timer);
          resolve(value);
        },
        reject: (error) => {
          clearTimeout(timer);
          reject(error);
        },
      });
      child.stdin.write(`${JSON.stringify({ jsonrpc: "2.0", id, method, params })}\n`);
    });
  }

  try {
    await request("initialize", {
      protocolVersion: "2024-11-05",
      capabilities: {},
      clientInfo: { name: "wiki-project-wxp-check", version: "1.0.0" },
    });
    child.stdin.write(`${JSON.stringify({
      jsonrpc: "2.0",
      method: "notifications/initialized",
    })}\n`);

    const listed = await request("tools/list");
    const names = (listed.tools || []).map((tool) => tool.name);
    const missing = REQUIRED_TOOLS.filter((name) => !names.includes(name));
    if (missing.length) {
      throw new Error(`缺少核心工具：${missing.join("、")}`);
    }

    const summary = {
      status: "ok",
      mode: args.mode,
      requiredTools: [...REQUIRED_TOOLS].sort(),
      toolCount: names.length,
    };

    if (args.mode === "platform") {
      const classResult = await request("tools/call", {
        name: "model_query_class_id",
        arguments: {
          projectName: args.project,
          moduleName: args.module || "",
          tableName: args.table,
        },
      });
      const classData = extractJsonContent(classResult);
      if (!classData.classId) {
        throw new Error("未返回 classId，请检查项目、模块和表名。");
      }

      const tableResult = await request("tools/call", {
        name: "table_analysis",
        arguments: {
          projectName: args.project,
          tableName: args.table,
        },
      });
      const tableData = extractJsonContent(tableResult);
      summary.table = args.table;
      summary.fieldCount = Number.isInteger(tableData.fieldCount)
        ? tableData.fieldCount
        : (Array.isArray(tableData.fields) ? tableData.fields.length : 0);
      summary.indexCount = Array.isArray(tableData.indexes) ? tableData.indexes.length : 0;
      summary.lineageCount = countLineage(tableData.fields);
    }

    process.stdout.write(`${JSON.stringify(summary)}\n`);
  } finally {
    lines.close();
    child.stdin.end();
    child.kill();
  }
}


main().catch((error) => {
  process.stderr.write(`wxp-mcp 检测失败：${error.message}\n`);
  process.exitCode = 1;
});
