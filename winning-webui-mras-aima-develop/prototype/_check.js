const fs = require("fs");
const html = fs.readFileSync("查故障原型.html", "utf8");
const m = html.match(/<script>([\s\S]*?)<\/script>/);
try { new Function(m[1]); console.log("JS syntax OK"); }
catch (e) { console.log("SYNTAX ERROR: " + e.message); process.exit(1); }

const checks = ["validateBtn","validateArea","aiValidateBtn","aiValidateArea","function validateSql","function renderValidateResult","🔍 校验 SQL"];
checks.forEach(t => console.log((html.includes(t) ? "OK  " : "MISS ") + t));
console.log("应用按钮文案:", html.includes(">应用<") ? "已改 OK" : "未改");
console.log("旧『应用并执行』残留:", html.includes("应用并执行") ? "仍存在" : "已清除");
