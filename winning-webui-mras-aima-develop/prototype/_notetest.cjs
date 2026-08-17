// ---- DOM stub ----
const elCache = {};
function mkEl(){
  const el = {
    _listeners:{}, style:{}, dataset:{}, textContent:"", innerHTML:"", value:"", disabled:false,
    classList:{ _s:new Set(), add(c){this._s.add(c);}, remove(c){this._s.delete(c);}, contains(c){return this._s.has(c);} },
    addEventListener(t,fn){ (this._listeners[t]||(this._listeners[t]=[])).push(fn); },
    querySelector(){ return mkEl(); },
    querySelectorAll(){ return []; },
    appendChild(){}, removeChild(){}, setAttribute(){}, getAttribute(){return null;}
  };
  return el;
}
const document = {
  getElementById(id){ return elCache[id]||(elCache[id]=mkEl()); },
  querySelector(){ return mkEl(); },
  querySelectorAll(){ return []; },
  createElement(){ return mkEl(); }
};
const window = { gtag:()=>{} };
global.document = document;
global.window = window;

// ---- load script ----
const fs = require('fs');
const h = fs.readFileSync('查故障原型.html','utf8');
const script = h.match(/<script>([\s\S]*?)<\/script>/)[1];
const factory = new Function(script + "\nreturn {state, buildClarifyPrefill};");
const api = factory();
const { state, buildClarifyPrefill } = api;
state.caliberName = "住院出院口径";
state.dateRange = "2024-01-01 ~ 2024-01-31";

let pass=0, fail=0;
function assert(name, cond){ if(cond){pass++; console.log("  ✓ "+name);} else {fail++; console.log("  ✗ FAIL: "+name);} }
function fire(){ const b=document.getElementById("clarifyAllBtn"); (b._listeners.click||[]).forEach(fn=>fn()); }
function reset(){ state.clarifyExtra=new Set(); document.getElementById("extraNote").value=""; document.getElementById("missNote").value=""; document.getElementById("clariResult").innerHTML=""; document.getElementById("toast").textContent=""; }

// 场景1：仅填写数据多了说明，未选患者、未填数据少了
reset();
document.getElementById("extraNote").value = "需要排除外科全部患者";
fire();
let r1 = document.getElementById("clariResult").innerHTML;
assert("note-only 仍生成数据多了分段", r1.includes("数据多了"));
assert("note-only 副标题标明未勾选具体患者", r1.includes("你说明的「数据多了」情况（未勾选具体患者）"));
assert("note-only 结果包含说明原文", r1.includes("需要排除外科全部患者"));
assert("note-only 未触发阻断 toast", document.getElementById("toast").textContent==="");

// 场景2：啥都没填 → 阻断
reset();
fire();
assert("空场景被阻断（无澄清结果）", document.getElementById("clariResult").innerHTML==="");
assert("空场景弹出提示 toast", document.getElementById("toast").textContent.includes("数据多了"));

// 场景3：仅勾选患者
reset();
state.clarifyExtra = new Set(["den:E202401000123","num:E202401000456"]);
fire();
let r3 = document.getElementById("clariResult").innerHTML;
assert("患者-only 生成数据多了分段", r3.includes("数据多了"));
assert("患者-only 副标题含计数位", r3.includes("位"));
assert("患者-only 不含未勾选具体患者", !r3.includes("未勾选具体患者"));

// 场景4：勾选患者 + 说明
reset();
state.clarifyExtra = new Set(["den:E202401000123"]);
document.getElementById("extraNote").value = "含测试数据";
fire();
let r4 = document.getElementById("clariResult").innerHTML;
assert("患者+note 含数据多了", r4.includes("数据多了"));
assert("患者+note 含补充说明原文", r4.includes("含测试数据"));

// 场景5：仅数据少了
reset();
document.getElementById("missNote").value = "某科室整月缺失";
fire();
let r5 = document.getElementById("clariResult").innerHTML;
assert("仅数据少了 生成数据少了分段", r5.includes("数据少了"));
assert("仅数据少了 不含数据多了", !r5.includes(">数据多了<"));

// 场景6：AI 预填（note-only）
reset();
state.clarifyExtra = new Set();
document.getElementById("extraNote").value = "需要排除外科全部患者";
let pf = buildClarifyPrefill();
assert("AI预填 note-only 含【数据多了】", pf.includes("【数据多了】"));
assert("AI预填 note-only 含未勾选提示", pf.includes("未勾选具体患者"));
assert("AI预填 note-only 含说明原文", pf.includes("需要排除外科全部患者"));

// 场景7：AI 预填（空）→ 不应含【数据多了】
reset();
let pf2 = buildClarifyPrefill();
assert("AI预填 空场景不含【数据多了】", !pf2.includes("【数据多了】"));

console.log(`\n结果：${pass} 通过 / ${fail} 失败`);
process.exit(fail?1:0);
