const fs = require('fs');
const src = fs.readFileSync('_syntaxcheck.cjs', 'utf8');

// ---- DOM 桩 ----
const elCache = {};
function makeEl(id) {
  const el = {
    id, value: '', disabled: false, className: '', style: {}, dataset: {},
    classList: {
      _s: new Set(),
      add(c){ this._s.add(c); },
      remove(c){ this._s.delete(c); },
      contains(c){ return this._s.has(c); },
      toggle(c, f){ if(f===undefined){ this._s.has(c)?this._s.delete(c):this._s.add(c); } else { f?this._s.add(c):this._s.delete(c); } },
    },
    addEventListener(){}, appendChild(){}, removeChild(){},
    querySelector(){ return makeEl('q'); }, querySelectorAll(){ return []; },
  };
  let _t='', _h='';
  Object.defineProperty(el,'textContent',{get(){return _t;},set(v){_t=v;}});
  Object.defineProperty(el,'innerHTML',{get(){return _h;},set(v){_h=v;}});
  return el;
}
const tabManual = makeEl('tabM'); tabManual.dataset.tab='manual';
const tabAi = makeEl('tabA'); tabAi.dataset.tab='ai';
const document = {
  getElementById(id){ return elCache[id] ??= makeEl(id); },
  createElement(){ return makeEl('dyn'); },
  querySelector(){ return makeEl('q'); },
  querySelectorAll(sel){ return sel==='.edt-tab' ? [tabManual, tabAi] : []; },
  addEventListener(){},
};
const window = {};
const localStorage = { getItem(){ return null; }, setItem(){} };

// ---- 整体加载脚本，暴露关键函数 ----
let api;
try {
  const factory = new Function('document','window','localStorage','setTimeout','clearTimeout','console','crypto',
    src + '\n;return {openCheck, buildClarifyPrefill, state, getFlowNodes:()=>flowNodes};');
  api = factory(document, window, localStorage, setTimeout, clearTimeout, console, globalThis.crypto);
} catch (e) {
  console.error('脚本加载失败:', e.message);
  process.exit(1);
}

// ---- 准备状态：模拟用户在第3步做了澄清 ----
api.state.caliberId = 'c1';
api.state.indicator = '门诊人次';
api.state.caliberName = '默认口径';
api.state.dateRange = '2026-07-01 ~ 2026-07-31';
api.state.clarifyExtra = new Set(['num:E20260715001', 'num:E20260715002', 'den:E20260715099']);
document.getElementById('missNote').value = '康复科 2026-07-15 的门诊未计入统计';

// ---- 触发：从澄清结果进入核查 ----
let threw = null;
try { api.openCheck({ fromClarify: true }); } catch (e) { threw = e; }
if (threw) { console.error('openCheck 执行抛错:', threw.message, '\n', threw.stack); process.exit(1); }

// ---- 断言 ----
let pass=0, fail=0;
function assert(name, cond){ if(cond){pass++;console.log('  ✓ '+name);} else {fail++;console.log('  ✗ '+name);} }

const s = api.state;
assert('进入核查后默认选中源表抽取(s1)节点', s.selectedNode === 's1');
const fp = document.getElementById('aiPrompt').value;
assert('AI 文本域已被填充', fp && fp.length > 0);
assert('带入「数据多了」说明', fp.includes('【数据多了】'));
assert('带入「数据少了」说明', fp.includes('【数据少了】'));
assert('指向源表抽取(s1)节点', fp.includes('源表抽取(s1)'));
assert('列出具体多了的患者 encounterId', fp.includes('E20260715001') && fp.includes('E20260715099'));
assert('区分分子侧/分母侧', fp.includes('分子侧') && fp.includes('分母侧'));
assert('AI 修改面板已显示', document.getElementById('panelAi').style.display === 'block');
assert('直接编辑面板已隐藏', document.getElementById('panelManual').style.display === 'none');
assert('AI tab 标记为 active', tabAi.classList.contains('active'));
assert('手动 tab 未 active', !tabManual.classList.contains('active'));

// ---- 反向断言：普通进入核查不应预设 ----
// 真实浏览器中 showNodeDetail(null) 会销毁并重建详情面板（aiPrompt 不复用），此处模拟该重建
document.getElementById('aiPrompt').value = '';
api.openCheck(); // 不带 fromClarify
assert('普通进入不默认选中 s1', api.state.selectedNode === null);
assert('普通进入不填充 AI 文本域', document.getElementById('aiPrompt').value === '');
assert('普通进入后 pendingAiPrefill 已消费清空', api.state._pendingAiPrefill === null);

console.log(`\n结果：${pass} 通过, ${fail} 失败`);
process.exit(fail ? 1 : 0);
