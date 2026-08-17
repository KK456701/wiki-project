const fs = require('fs');
const src = fs.readFileSync('_syntaxcheck.cjs', 'utf8');

// 截取 updateClarifyExtraCount + clearClarifyExtra 两个函数定义（不含 addEventListener 绑定）
const start = src.indexOf('function updateClarifyExtraCount');
const end = src.indexOf('$("clearExtraBtn").addEventListener');
if (start < 0 || end < 0) { console.error('FAIL: 没能定位函数'); process.exit(1); }
const code = src.slice(start, end);

// 最小 DOM 桩
const elements = {};
function makeEl() {
  return { textContent: '', disabled: false, value: '',
    classList: { toggle(){}, add(){}, remove(){} }, addEventListener(){} };
}
const document = { getElementById: (id) => (elements[id] ??= makeEl()) };
const $ = (id) => document.getElementById(id);

// 状态桩（含 2 个已勾选患者）
const state = { clarifyExtra: new Set(['num:1', 'num:2']), detailKind: 'num', detailPage: 1, patientCache: {}, rows: [] };
function renderDetailPanel() { state._renderCalled = (state._renderCalled || 0) + 1; }

const factory = new Function('state', '$', 'renderDetailPanel',
  code + '\nreturn {updateClarifyExtraCount, clearClarifyExtra};');
const { updateClarifyExtraCount, clearClarifyExtra } = factory(state, $, renderDetailPanel);

let pass = 0, fail = 0;
function assert(name, cond) {
  if (cond) { pass++; console.log('  ✓ ' + name); }
  else { fail++; console.log('  ✗ ' + name); }
}

// 1. 初始有 2 个勾选
updateClarifyExtraCount();
assert('勾选 2 位时文案为「已选 2 位患者」', $('clarifyExtraCount').textContent === '已选 2 位患者');
assert('勾选 >0 时清空按钮可用', $('clearExtraBtn').disabled === false);

// 2. 清空
clearClarifyExtra();
assert('清空后集合为空', state.clarifyExtra.size === 0);
assert('清空后调用了明细重渲染', (state._renderCalled || 0) >= 1);
updateClarifyExtraCount();
assert('清空后文案为「已选 0 位患者」', $('clarifyExtraCount').textContent === '已选 0 位患者');
assert('清空后按钮禁用', $('clearExtraBtn').disabled === true);

// 3. 重复清空不报错
let threw = false;
try { clearClarifyExtra(); } catch (e) { threw = true; }
assert('空集合再次清空不抛错', !threw);

console.log(`\n结果：${pass} 通过, ${fail} 失败`);
process.exit(fail ? 1 : 0);
