// DOM-stub integration test: AI 初诊 scans injected test-data patients across 分子/分母
const fs = require("fs");
const vm = require("vm");

const html = fs.readFileSync("查故障原型.html", "utf8");
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];

// ---- DOM stub ----
const elCache = {};
function mkEl() {
  const el = {
    className: "", innerHTML: "", textContent: "", value: "",
    style: {}, dataset: {},
    classList: { _s: new Set(),
      add(c){this._s.add(c);}, remove(c){this._s.delete(c);},
      contains(c){return this._s.has(c);},
      toggle(c,f){ if(f===undefined){ this._s.has(c)?this._s.delete(c):this._s.add(c);} else { f?this._s.add(c):this._s.delete(c);} } },
    addEventListener(){}, appendChild(){},
    querySelector(){ return mkEl(); }, querySelectorAll(){ return []; },
  };
  return el;
}
const document = {
  getElementById(id){ return elCache[id] || (elCache[id] = mkEl()); },
  querySelector(){ return mkEl(); },
  querySelectorAll(){ return []; },
};
const ctx = { document, setTimeout, clearTimeout, console, Math, Date, JSON, crypto: { randomUUID(){ return "uuid"; } } };
ctx.window = ctx; ctx.globalThis = ctx;

const epilogue = "\n;globalThis.__api={state,renderAiDiagnosis,genPatients,buildRows,seedFor,metaNum,metaDen,INDICATOR_META};";
vm.createContext(ctx);
vm.runInContext(script + epilogue, ctx);

const api = ctx.__api;
let pass = 0, fail = 0;
function assert(name, cond){ if(cond){ pass++; console.log("  ✓ "+name); } else { fail++; console.log("  ✗ "+name); } }

// 1) 注入逻辑：至少部分「分母」明细应含测试数据患者
let injectedTotal = 0, injectedAny = false;
["门诊人次","住院死亡率","药占比"].forEach(ind=>{
  const rows = api.buildRows(ind, ind==="门诊人次"?"c1":(ind==="住院死亡率"?"d1":"y1"));
  rows.forEach(r=>{
    if(r.den>0){
      const key=r.dept+"|"+r.date+"|den";
      const list = api.genPatients(r.dept,r.date,"den",r.den,api.seedFor(key));
      const hit = list.some(p=>/测试|test/i.test(p.name)||/测试|test/i.test(p.dept)||/测试|test/i.test(p.area));
      if(hit){ injectedAny = true; injectedTotal += list.filter(p=>/测试|test/i.test(p.name)||/测试|test/i.test(p.dept)||/测试|test/i.test(p.area)).length; }
    }
  });
});
assert("各指标分母明细中存在注入的测试数据患者", injectedAny);
assert("注入的测试数据患者总数 (>0)", injectedTotal > 0);

// 2) 注入保持总数不变（不破坏明细计数）
const r0 = api.buildRows("门诊人次","c1")[0];
const key0 = r0.dept+"|"+r0.date+"|den";
assert("genPatients 返回长度 == count（总数不变）", api.genPatients(r0.dept,r0.date,"den",r0.den,api.seedFor(key0)).length === r0.den);

// 3) 全量扫描：renderAiDiagnosis 对默认场景应命中并标红 warn
api.state.indicator = "门诊人次";
api.state.caliberName = "门诊人次·口径1";
api.state.rows = api.buildRows("门诊人次","c1");
api.state.patientCache = {};
api.renderAiDiagnosis();
const box = document.getElementById("aiDiag");
assert("aiDiag 渲染为 warn 态", box.className === "ai-diag warn");
assert("aiDiag 内含「疑似测试数据」文案", /疑似测试数据/.test(box.innerHTML));
assert("aiDiag 列出了具体命中记录（含 enc）", /enc E/.test(box.innerHTML));

// 4) 无测试数据时显示 ok 态（构造纯正常数据验证分支）——用仅分子小计且不含测试
api.state.rows = [{dept:"内科",date:"2026-07-01",num:0,den:0,flag:"none"}];
api.state.patientCache = {};
api.renderAiDiagnosis();
assert("无命中时 aiDiag 为 ok 态", document.getElementById("aiDiag").className === "ai-diag ok");

console.log("\n结果: "+pass+" 通过, "+fail+" 失败");
process.exit(fail ? 1 : 0);
