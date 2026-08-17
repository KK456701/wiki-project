// DOM-stub test: 第1步按钮改名 + 第2步进入后自动开始基础检查（无需手动点击）
const fs = require("fs"), vm = require("vm");
const html = fs.readFileSync("查故障原型.html", "utf8");
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];

const elCache = {};
function mkEl(){ return {
  className:"", innerHTML:"", textContent:"", value:"", style:{}, dataset:{},
  classList:{ _s:new Set(), add(c){this._s.add(c);}, remove(c){this._s.delete(c);},
    contains(c){return this._s.has(c);},
    toggle(c,f){ if(f===undefined){ this._s.has(c)?this._s.delete(c):this._s.add(c);} else { f?this._s.add(c):this._s.delete(c);} } },
  addEventListener(){}, appendChild(){}, querySelector(){return mkEl();}, querySelectorAll(){return [];} };
}
const document = { getElementById(id){ return elCache[id]||(elCache[id]=mkEl()); },
  createElement(){ return mkEl(); },
  querySelector(){return mkEl();}, querySelectorAll(){return [];} };
const ctx = { document, setTimeout, clearTimeout, console, Math, Date, JSON, crypto:{randomUUID(){return "u";}} };
ctx.window=ctx; ctx.globalThis=ctx;
const epilogue="\n;globalThis.__api={state,goBasic,startBasicCheck,queryBtn};";
vm.createContext(ctx); vm.runInContext(script+epilogue, ctx);
const api = ctx.__api;

let pass=0, fail=0;
function assert(n,c){ if(c){pass++;console.log("  ✓ "+n);} else {fail++;console.log("  ✗ "+n);} }

// 1) 第1步按钮文案已改为「开始排查」（直接从 HTML 源码校验）
assert("HTML 中 queryBtn 文案为『开始排查』", /id="queryBtn"[^>]*>开始排查</.test(html));

// 2) 进入第2步（未通过）应自动开始检查：startCheckBtn 隐藏、首个 gate 立即 running
api.state.indicator = "门诊人次";
api.state.caliberId = "c1"; api.state.caliberName = "门诊人次·口径1";
api.state.basicCheckPassed = false;
api.goBasic();
const sc = document.getElementById("startCheckBtn");
assert("进入第2步后『开始基础检查』按钮隐藏", sc.style.display === "none");
assert("进入第2步后第1关立即进入 running（自动开始）", api.state.gateStatus[0] === "running");
assert("进入第2步后 basicResult 未显示", document.getElementById("basicResult").style.display === "none");

console.log("\n结果: "+pass+" 通过, "+fail+" 失败");
process.exit(fail?1:0);
