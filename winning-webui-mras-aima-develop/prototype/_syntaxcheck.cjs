
/* =================== Mock 数据 =================== */
const CALIBERS = {
  "门诊人次":[
    {id:"c1",name:"口径1 · 门诊挂号就诊",desc:"以门诊挂号记录为统计口径，含现场挂号与预约挂号。"},
    {id:"c2",name:"口径2 · 门诊收费记录",desc:"以门诊收费明细为统计口径，含自费与医保结算。"},
    {id:"c3",name:"口径3 · 含急诊转门诊",desc:"在口径1基础上，纳入急诊留观转门诊的就诊人次数。"},
  ],
  "住院死亡率":[
    {id:"d1",name:"口径1 · 出院死亡",desc:"以出院小结中死亡原因为统计口径。"},
    {id:"d2",name:"口径2 · 在院死亡",desc:"含在院期间死亡未出院的记录。"},
  ],
  "药占比":[
    {id:"y1",name:"口径1 · 住院药占比",desc:"住院药品收入 / 住院医疗总收入。"},
    {id:"y2",name:"口径2 · 门急诊药占比",desc:"门急诊药品收入 / 门急诊医疗总收入。"},
  ],
};

// 各指标的分子 / 分母定义（用于列表列名与语义说明）
const INDICATOR_META = {
  "门诊人次":   { numName:"就诊人次", denName:"挂号人次" },
  "住院死亡率": { numName:"死亡人数", denName:"出院人数" },
  "药占比":     { numName:"用药患者数", denName:"就诊患者数" },
};
function metaNum(){ return (INDICATOR_META[state.indicator]||{numName:"分子"}).numName; }
function metaDen(){ return (INDICATOR_META[state.indicator]||{denName:"分母"}).denName; }
function fmt(n){ return (typeof n==="number") ? n.toLocaleString("zh-CN") : n; }

// 生成统计明细：每行含 分子(num) / 分母(den)，并注入演示用“缺失/增多”
function buildRows(indicator, caliberId){
  let base;
  if(indicator==="住院死亡率"){
    base=[["内科","2026-07-01",29],["外科","2026-07-01",43],["儿科","2026-07-01",3],
          ["妇产科","2026-07-01",2],["急诊科","2026-07-01",76],["骨科","2026-07-01",14],
          ["内科","2026-07-02",31],["外科","2026-07-02",40],["儿科","2026-07-02",4],
          ["妇产科","2026-07-02",2],["急诊科","2026-07-02",71],["骨科","2026-07-02",15]];
  }else if(indicator==="药占比"){
    base=[["内科","2026-07-01",915],["外科","2026-07-01",1429],["儿科","2026-07-01",484],
          ["妇产科","2026-07-01",631],["急诊科","2026-07-01",2019],["骨科","2026-07-01",540],
          ["内科","2026-07-02",928],["外科","2026-07-02",1401],["儿科","2026-07-02",501],
          ["妇产科","2026-07-02",648],["急诊科","2026-07-02",1988],["骨科","2026-07-02",551]];
  }else{
    base=[["内科","2026-07-01",305],["外科","2026-07-01",342],["儿科","2026-07-01",210],
          ["妇产科","2026-07-01",156],["急诊科","2026-07-01",612],["骨科","2026-07-01",98],
          ["内科","2026-07-02",318],["外科","2026-07-02",330],["儿科","2026-07-02",225],
          ["妇产科","2026-07-02",161],["急诊科","2026-07-02",598],["骨科","2026-07-02",104]];
  }
  // 分母按指标语义放大；此处用基础值 num0 计算分母，后续口径异常只改分子，分母保持合理
  let rows = base.map(r=>{
    const num0=r[2];
    let den=num0;
    if(indicator==="住院死亡率") den=Math.round(num0/0.0125);   // 出院人数
    else if(indicator==="药占比") den=Math.round(num0/0.38);     // 就诊患者数
    else den=Math.round(num0/0.93);                             // 挂号人次
    return {dept:r[0],date:r[1],num:num0,den,flag:"none"};
  });
  const set=(d,dt,fn)=>{const x=rows.find(r=>r.dept===d&&r.date===dt); if(x) fn(x);};
  if(indicator==="门诊人次"){
    if(caliberId==="c1"){ set("内科","2026-07-01",x=>{x.num=0;x.flag="miss";}); set("急诊科","2026-07-01",x=>{x.num=890;x.flag="more";}); }
    else if(caliberId==="c2"){ set("妇产科","2026-07-01",x=>{x.num=0;x.flag="miss";}); }
    else if(caliberId==="c3"){ set("急诊科","2026-07-01",x=>{x.num=905;x.flag="more";}); }
  }else if(indicator==="住院死亡率"){
    if(caliberId==="d1"){ set("儿科","2026-07-01",x=>{x.num=0;x.flag="miss";}); }
  }else if(indicator==="药占比"){
    if(caliberId==="y1"){ set("内科","2026-07-01",x=>{x.num=0;x.flag="miss";}); }
  }
  return rows;
}

/* =================== 患者明细（mock 生成） =================== */
const SURNAMES=["张","王","李","赵","刘","陈","杨","黄","周","吴","徐","孙","马","朱","胡","郭","何","高","林","罗"];
const GIVEN=["伟","芳","娜","敏","静","丽","强","磊","军","洋","勇","艳","杰","娟","涛","明","超","霞","平","刚"];
const AREAS={"内科":["内科一区","内科二区","内科三区"],"外科":["外科一区","外科二区"],"儿科":["儿科病区"],
  "妇产科":["妇产科一区","妇产科二区"],"急诊科":["急诊抢救区","急诊观察区"],"骨科":["骨科一区","骨科二区"]};

function seedFor(key){ let h=2166136261; for(let i=0;i<key.length;i++){ h=((h^key.charCodeAt(i))*16777619)&0x7fffffff; } return h||1; }
function genPatients(dept,date,kind,count,seed){
  const list=[]; let s=seed; const areas=AREAS[dept]||["综合病区"]; const dn=date.replace(/-/g,"");
  for(let i=0;i<count;i++){
    s=(s*1103515245+12345)&0x7fffffff;
    const name=SURNAMES[s%SURNAMES.length]+GIVEN[(s>>5)%GIVEN.length];
    s=(s*1103515245+12345)&0x7fffffff;
    const area=areas[s%areas.length];
    s=(s*1103515245+12345)&0x7fffffff;
    const enc="E"+dn+String(100000+(s%899999)).slice(0,6);
    list.push({name,enc,dept,area});
  }
  // —— 演示用：在部分「分母」明细中确定性注入少量疑似测试数据患者（替换末尾，保持总数不变）——
  const kseed=seedFor(dept+"|"+date+"|"+kind);
  if(kind==="den" && count>=100 && (kseed % 4 === 0)){
    const inject=1+(kseed%2);                 // 注入 1~2 位
    const surname=SURNAMES[(kseed>>3)%SURNAMES.length];
    const given=GIVEN[(kseed>>5)%GIVEN.length];
    for(let j=0;j<inject;j++){
      const idx=count-1-j;
      const old=list[idx]||{area:areas[0]};
      const mode=(kseed+j)%3;                  // 0:姓名含「测试」 1:科室=「测试科」 2:病区含「test」
      let nm,dp,ar;
      if(mode===0){ nm="测试"+surname+given; dp=dept; ar=old.area; }
      else if(mode===1){ nm=surname+given; dp="测试科"; ar=old.area; }
      else { nm="Test"+surname; dp=dept; ar="test 病区"; }
      list[idx]={name:nm, enc:"E"+dn+String(900000+(kseed%99999)).slice(0,6), dept:dp, area:ar};
    }
  }
  return list;
}

// 数据链路节点（门诊人次·口径1 为完整示例）
function buildFlow(caliberId){
  return [
    {id:"t1",type:"TABLE",color:"var(--primary)",title:"业务库 · 门诊就诊记录 mz_jz",
     desc:"HIS 业务库门诊就诊主表，存储每次门诊就诊记录。",tables:["mz_jz"],dbRole:"业务库"},
    {id:"s1",type:"SOURCE_EXTRACT_SQL",color:"var(--warning)",title:"源表抽取 SQL",
     desc:"从 mz_jz 抽取门诊就诊记录，按科室与日期投影。",tables:["mz_jz"],dbRole:"业务库",sqlKind:"源表抽取",
     sql:"SELECT dept_id, visit_date, patient_id\nFROM mz_jz\nWHERE visit_type = '门诊'"},
    {id:"e1",type:"EXTENDED_EVENT_SQL",color:"var(--secondary)",title:"拓展事件 SQL",
     desc:"关联挂号事件，补充就诊类型字段。",tables:["src_mz","mz_register"],dbRole:"同步/ETL",sqlKind:"拓展事件",
     sql:"SELECT a.*, b.register_type\nFROM src_mz a\nLEFT JOIN mz_register b ON a.patient_id = b.patient_id"},
    {id:"o1",type:"OVERVIEW_SQL",color:"var(--success)",title:"概览统计 SQL（核心口径）",core:true,
     desc:"按科室 + 日期统计就诊人次数，是本次口径的核心计算节点。",tables:["ext_mz_event"],dbRole:"真实库",sqlKind:"概览统计",
     sql:"SELECT dept_id, visit_date, COUNT(*) AS visit_cnt\nFROM ext_mz_event\nGROUP BY dept_id, visit_date"},
    {id:"d1",type:"DEPARTMENT_SQL",color:"var(--success)",title:"科室统计 SQL",
     desc:"按科室聚合概览结果，输出指标最终结果。",tables:["overview_mz"],dbRole:"真实库",sqlKind:"科室统计",
     sql:"SELECT dept_id, SUM(visit_cnt) AS total_cnt\nFROM overview_mz\nGROUP BY dept_id"},
    {id:"r1",type:"RESULT",color:"var(--primary)",title:"指标结果 · 门诊人次",
     desc:"门诊人次最终指标结果，供前端展示与确认。",tables:[],dbRole:"真实库"},
  ];
}

// 各 SQL 节点的结果列名
const RESULT_HEADER = {
  s1:["科室","统计日期","抽取行数"],
  e1:["科室","统计日期","抽取行数"],
  o1:["科室","统计日期","就诊人数"],
  d1:["科室","就诊人数"],
};

// 各 SQL 节点的原结果（mock）
const ORIGINAL_RESULT = {
  s1:[["内科","2026-07-01","1,205 行"],["外科","2026-07-01","1,388 行"],["急诊科","2026-07-01","2,940 行"]],
  e1:[["内科","2026-07-01","1,205 行"],["外科","2026-07-01","1,388 行"]],
  // 核心节点原结果：含演示用的“缺失/增多”偏差
  o1:[["内科","2026-07-01",0],["外科","2026-07-01",342],["儿科","2026-07-01",210],
      ["妇产科","2026-07-01",156],["急诊科","2026-07-01",890],["骨科","2026-07-01",98]],
  d1:[["内科",305],["外科",342],["儿科",210],["妇产科",156],["急诊科",890],["骨科",98]],
};

// 模拟“执行编辑后的 SQL”：返回与原结果有差异的新结果（演示对比闭环）
function simulateExec(nodeId, editedSql){
  const orig = ORIGINAL_RESULT[nodeId] || [];
  if(nodeId==="o1"){
    // 模拟：编辑 SQL 后，缺失的“内科”被补回、增多的“急诊科”被修正
    return [
      ["内科","2026-07-01",305],["外科","2026-07-01",342],["儿科","2026-07-01",210],
      ["妇产科","2026-07-01",156],["急诊科","2026-07-01",612],["骨科","2026-07-01",98]
    ];
  }
  if(nodeId==="d1"){
    return [["内科",305],["外科",342],["儿科",210],["妇产科",156],["急诊科",612],["骨科",98]];
  }
  // 其它节点：轻微变化以示“已重新执行”
  return orig.map(r=> r.slice());
}

/* =================== 状态 =================== */
let state = {
  indicator:"", caliberId:"", caliberName:"", rows:[], dateRange:"",
  selectedNode:null, editedSql:{}, execResult:{},
  gateStatus:["pending","pending","pending"], basicCheckPassed:false,
  detailKind:null,          // 当前查看的患者明细类型：null | 'num' | 'den'
  detailPage:1,             // 当前明细页码
  patFilters:{name:"",enc:"",dept:""},  // 患者明细筛选：姓名 / encounterId / 科室
  patientCache:{},          // `${dept}|${date}|${kind}` -> 患者数组（聚合用）
  clarifyExtra:new Set()    // 标记「数据多了」的患者：`${kind}:${enc}`
};
let chainExecuted = false;   // 是否已整体执行

// 基础检查 3 关定义
const GATE_DEFS = [
  {id:1, name:"数据结构校验", items:[
    "业务库与真实库连接正常",
    "已核对当前口径用到的数据表、字段及真实库计算结构，未发现会阻断当前计算的缺表、缺字段或结构不一致问题"
  ]},
  {id:2, name:"事件配置校验", items:[
    "知识库候选事件 1 个",
    "现场未发现多口径同时启用",
    "本次校验已重新计算当前口径，状态为 SUCCESS"
  ]},
  {id:3, name:"现场数值校验", items:[
    "第二步本次计算成功",
    "当前统计窗口存在 418 条可计算数据"
  ]},
];
const delay = ms => new Promise(r=>setTimeout(r,ms));

/* =================== 步骤1 逻辑 =================== */
const $ = id => document.getElementById(id);
const indicatorSel=$("indicatorSel"), caliberSel=$("caliberSel"), queryBtn=$("queryBtn");

indicatorSel.addEventListener("change",()=>{
  state.indicator = indicatorSel.value;
  caliberSel.innerHTML = '<option value="">— 请选择口径 —</option>';
  if(state.indicator){
    CALIBERS[state.indicator].forEach(c=>{
      const o=document.createElement("option");o.value=c.id;o.textContent=c.name;caliberSel.appendChild(o);
    });
    caliberSel.disabled=false;
    $("caliberCount").textContent = `该指标共 ${CALIBERS[state.indicator].length} 套口径`;
  }else{
    caliberSel.disabled=true;
    $("caliberCount").textContent="";
  }
  $("caliberDesc").textContent="";
  refreshQueryBtn();
});

caliberSel.addEventListener("change",()=>{
  const c = CALIBERS[state.indicator]?.find(x=>x.id===caliberSel.value);
  $("caliberDesc").textContent = c? c.desc : "";
  refreshQueryBtn();
});

function refreshQueryBtn(){
  queryBtn.disabled = !(state.indicator && caliberSel.value);
}

queryBtn.addEventListener("click",()=>{
  const cal = CALIBERS[state.indicator].find(x=>x.id===caliberSel.value);
  state.caliberId = cal.id; state.caliberName = cal.name;
  state.rows = buildRows(state.indicator, cal.id);
  state.dateRange = `${$("dateStart").value} ~ ${$("dateEnd").value}`;
  state.basicCheckPassed = false;   // 新指标/口径需重新检查
  goBasic();
  refreshStepper();
});

/* =================== 步骤2：基础检查 =================== */
function goBasic(){
  setStep(2);
  showBaseView("viewBasic");
  if(state.basicCheckPassed){
    renderBasicGates();
    $("startCheckBtn").style.display="none";
    $("basicResult").style.display="block";
    $("basicFail").style.display="none";
  }else{
    startBasicCheck();   // 进入第 2 步即自动开始检查，无需手动点击「开始基础检查」
  }
}

function renderBasicGates(){
  const wrap=$("gateList"); wrap.innerHTML="";
  GATE_DEFS.forEach((g,i)=>{
    const st=state.gateStatus[i];
    const stMap={
      pending:['待执行','st-pending',''],
      running:['<span class="spinner"></span> 校验中…','st-running',''],
      pass:['✓ 通过','st-pass',''],
      fail:['✗ 未通过','st-fail','']
    };
    const [txt,cls,spin]=stMap[st];
    const icon = st==="pass" ? '<span class="ic" style="color:var(--success)">✓</span>'
      : st==="fail" ? '<span class="ic" style="color:var(--error)">✗</span>' : '';
    const itemsHtml = g.items.map(it=>`<li>${icon}<span>${it}</span></li>`).join("");
    const reason = st==="fail"
      ? `<div class="g-reason">未通过原因（演示）：第 ${g.id} 关校验失败，检查已停止，后续关卡不再执行。请排查后点击「重置检查」重新运行。</div>` : "";
    const el=document.createElement("div");
    el.className="gate"+(st==="pass"?" pass":st==="fail"?" fail":st==="running"?" running":"");
    el.innerHTML=`
      <div class="gate-head">
        <span class="g-num">第 ${g.id} 关</span>
        <span class="g-name">${g.name}</span>
        <span class="g-status ${cls}">${txt}</span>
      </div>
      <div class="g-body">
        <ul class="g-items">${itemsHtml}</ul>
        ${reason}
      </div>`;
    wrap.appendChild(el);
  });
}

async function startBasicCheck(){
  const failGate = parseInt($("failSim").value||"0",10);
  state.basicCheckPassed=false;
  $("startCheckBtn").style.display="none";
  $("basicResult").style.display="none";
  $("basicFail").style.display="none";
  state.gateStatus=["pending","pending","pending"];
  renderBasicGates();

  for(let i=0;i<GATE_DEFS.length;i++){
    state.gateStatus[i]="running"; renderBasicGates();
    await delay(850);
    if(failGate===i+1){
      state.gateStatus[i]="fail"; renderBasicGates();
      $("failText").textContent = `第 ${i+1} 关「${GATE_DEFS[i].name}」检查未通过，检查已停止，后续关卡不再执行。请排查后点击「重置检查」重新运行。`;
      $("basicFail").style.display="block";
      return;
    }
    state.gateStatus[i]="pass"; renderBasicGates();
  }
  state.basicCheckPassed=true;
  $("basicResult").style.display="block";
}

function resetBasicCheck(){
  startBasicCheck();   // 重置后自动重新执行检查
}

function enterDataConfirm(){
  setStep(3);
  showBaseView("view2");
  // 重置澄清状态
  $("clariResult").innerHTML="";
  const mn=$("missNote"); if(mn) mn.value="";
  const en=$("extraNote"); if(en) en.value="";
  // 重置展开与勾选状态
  state.detailKind=null; state.detailPage=1; state.patientCache={}; state.clarifyExtra=new Set();
  state.patFilters={name:"",enc:"",dept:""};
  $("patName").value=""; $("patEnc").value=""; $("patDept").value="";
  updateClarifyExtraCount();
  // 指标分子 / 分母 语义说明
  const meta=INDICATOR_META[state.indicator]||{numName:"分子",denName:"分母"};
  $("metricCaption").textContent=`分子 = ${meta.numName}　|　分母 = ${meta.denName}（点击顶部「分子 / 分母」卡片查看对应患者明细）`;
  $("numNameTip").textContent=meta.numName; $("denNameTip").textContent=meta.denName;
  renderStats();
  renderAiDiagnosis();
}

// AI 初诊：扫描分子 / 分母全部患者记录的姓名、科室、病区，标记含「测试 / test」字眼的疑似测试数据
function renderAiDiagnosis(){
  const box=$("aiDiag"); if(!box) return;
  const TEST_PAT=/测试|test/i;
  const findings=[]; const seen=new Set();
  const add=(type,p,kind)=>{
    const id=type+"|"+p.enc; if(seen.has(id)) return; seen.add(id);
    findings.push({type, name:p.name, enc:p.enc, dept:p.dept, area:p.area, kind});
  };
  ["num","den"].forEach(kind=>{
    state.rows.forEach(r=>{
      const cnt = kind==="num" ? r.num : r.den;
      if(cnt<=0) return;
      const key=r.dept+"|"+r.date+"|"+kind;
      const list=state.patientCache[key] || genPatients(r.dept,r.date,kind,cnt,seedFor(key));
      list.forEach(p=>{
        if(TEST_PAT.test(p.name)) add("患者姓名",p,kind);
        else if(TEST_PAT.test(p.dept)) add("科室",p,kind);
        else if(TEST_PAT.test(p.area)) add("病区",p,kind);
      });
    });
  });
  if(findings.length===0){
    box.className="ai-diag ok";
    box.innerHTML=`<div class="ai-diag-head">🤖 AI 初诊</div>
      <div class="ai-diag-body"><span class="ai-badge ok">未发现疑似测试数据</span>
      <span class="muted" style="font-size:12.5px;">已扫描分子 / 分母全部患者记录的姓名、科室、病区，未发现含「测试 / test」字眼的疑似测试数据。</span></div>`;
    return;
  }
  const TYPE_LABEL={ "患者姓名":"患者姓名含「测试 / test」", "科室":"科室名含「测试 / test」", "病区":"病区名含「测试 / test」" };
  const byType={};
  findings.forEach(f=>{ (byType[f.type]=byType[f.type]||[]).push(f); });
  const segs=Object.keys(byType).map(t=>{
    const arr=byType[t];
    const items=arr.slice(0,8).map(f=>`<div class="ai-item">${escapeHtml(f.name)}（enc ${escapeHtml(f.enc)} · ${escapeHtml(f.dept)} · ${escapeHtml(f.area)}）<span class="muted"> · ${f.kind==="num"?metaNum():metaDen()}</span></div>`).join("");
    const more = arr.length>8 ? `<div class="muted" style="font-size:12px;padding-top:4px;">…其余 ${arr.length-8} 条省略</div>` : "";
    return `<div class="ai-seg"><div class="ai-seg-head"><span class="ai-tag">${escapeHtml(TYPE_LABEL[t])}</span><span class="muted" style="font-size:12px;">${arr.length} 条</span></div><div class="ai-seg-body">${items}${more}</div></div>`;
  }).join("");
  box.className="ai-diag warn";
  box.innerHTML=`<div class="ai-diag-head">🤖 AI 初诊 <span class="ai-warn-badge">疑似测试数据 ${findings.length} 条</span></div>
    <div class="ai-diag-body">
      <p class="hint">检测到以下记录的名称中可能包含测试数据（含「测试 / test」字眼）。建议确认后，在上方「患者明细」中勾选这些患者并归入「数据多了」予以排除，或进入链路核查追溯来源。</p>
      ${segs}
    </div>`;
}

// 卡片汇总 + 患者明细面板渲染（无逐行表格，卡片直接驱动明细）
function renderStats(){
  const numTotal = state.rows.reduce((s,r)=>s+r.num,0);
  const denTotal = state.rows.reduce((s,r)=>s+r.den,0);
  $("numTotalVal").textContent = fmt(numTotal);
  $("denTotalVal").textContent = fmt(denTotal);
  $("rateVal").textContent = denTotal ? (numTotal/denTotal*100).toFixed(1)+"%" : "—";
  $("cardNum").classList.toggle("active", state.detailKind==="num");
  $("cardDen").classList.toggle("active", state.detailKind==="den");
  renderDetailPanel();
}

// 渲染「卡片驱动」的患者明细面板（初始提示请先选择；点击分子/分母卡片展开，收起后保留勾选）
function renderDetailPanel(){
  const panel=$("detailPanel");
  const empty=$("dpEmpty"); const tw=$("dpTableWrap");
  // 未选中分子 / 分母：显示提示，不展示表格（收起同样回到此处，勾选状态保留）
  if(!state.detailKind){
    panel.style.display="block";
    empty.style.display="block";
    tw.style.display="none";
    $("dpFilters").style.display="none";
    $("dpPager").innerHTML="";
    $("dpTitle").textContent="患者明细";
    $("dpCount").textContent="";
    return;
  }
  const kind=state.detailKind;
  const label=kind==="num"?metaNum():metaDen();
  const filtered=state.rows;
  const all=[];
  filtered.forEach(r=>{
    const count = kind==="num" ? r.num : r.den;
    if(count>0){
      const key=r.dept+"|"+r.date+"|"+kind;
      if(!state.patientCache[key]){
        state.patientCache[key]=genPatients(r.dept,r.date,kind,count,seedFor(key));
      }
      all.push(...state.patientCache[key]);
    }
  });
  // 科室下拉：用「未筛选」的全量科室集合，避免按姓名/ID筛选时选项跳动
  const depts=[...new Set(all.map(p=>p.dept))].sort();
  const sel=$("patDept");
  const cur=sel.value;
  sel.innerHTML='<option value="">全部科室</option>'+depts.map(d=>`<option value="${escapeHtml(d)}">${escapeHtml(d)}</option>`).join("");
  if(depts.includes(cur)) sel.value=cur;

  // 按筛选条件过滤患者
  const f=state.patFilters;
  const fn=f.name.trim().toLowerCase(), fe=f.enc.trim().toLowerCase(), fd=f.dept;
  const matched = all.filter(pt=>{
    if(fn && !pt.name.toLowerCase().includes(fn)) return false;
    if(fe && !pt.enc.toLowerCase().includes(fe)) return false;
    if(fd && pt.dept!==fd) return false;
    return true;
  });

  const total=matched.length;
  $("dpTitle").textContent=`患者明细 · ${label}`;
  const fActive = fn||fe||fd ? "（已筛选）" : "";
  $("dpCount").textContent=fActive
    ? `筛选出 ${fmt(total)} / ${fmt(all.length)} 条患者记录`
    : `共 ${fmt(total)} 条患者记录（来自 ${filtered.length} 个科室·日期组合）`;
  const PAGE=8;
  const totalPages=Math.max(1,Math.ceil(total/PAGE));
  let p=state.detailPage||1; if(p>totalPages)p=totalPages;
  const start=(p-1)*PAGE;
  const slice=matched.slice(start,start+PAGE);
  empty.style.display="none";
  tw.style.display="block";
  $("dpFilters").style.display="flex";
  $("dpBody").innerHTML = total===0
    ? `<tr><td colspan="5" class="empty">${all.length===0?"该指标数值为 0，无患者明细":"无匹配的患者，请调整筛选条件"}</td></tr>`
    : slice.map(pt=>{
        const pk=kind+":"+pt.enc;
        const checked=state.clarifyExtra.has(pk)?"checked":"";
        return `<tr><td class="center"><input type="checkbox" class="pat-check" data-kind="${kind}" data-enc="${escapeHtml(pt.enc)}" ${checked}/></td>`
          +`<td>${escapeHtml(pt.name)}</td><td>${escapeHtml(pt.enc)}</td><td>${pt.dept}</td><td>${escapeHtml(pt.area)}</td></tr>`;
      }).join("");
  $("dpPager").innerHTML = panelPagerHtml(p,totalPages);
  panel.style.display="block";
}

// 明细面板分页控件（窗口式页码 + 上下页）
function panelPagerHtml(p,totalPages){
  if(totalPages<=1) return `<span class="pg-info">共 1 页</span>`;
  const mk=(n,label,disabled)=>`<button class="pg-btn" data-page="${n}" ${disabled?"disabled":""}>${label}</button>`;
  let btns=mk(p-1,"‹ 上一页",p<=1);
  const win=[];
  for(let n=1;n<=totalPages;n++){ if(n===1||n===totalPages||Math.abs(n-p)<=2) win.push(n); }
  let prev=null;
  win.forEach(n=>{
    if(prev!==null && n-prev>1) btns+=`<span class="pg-gap">…</span>`;
    btns+=`<button class="pg-btn ${n===p?"active":""}" data-page="${n}">${n}</button>`;
    prev=n;
  });
  btns+=mk(p+1,"下一页 ›",p>=totalPages);
  return `${btns}<span class="pg-info">第 ${p} / ${totalPages} 页</span>`;
}

// 顶部「分子 / 分母」卡片点击：展开 / 切换 / 收起患者明细
function toggleCard(kind){
  state.detailKind = state.detailKind===kind ? null : kind;
  state.detailPage=1;
  state.patFilters={name:"",enc:"",dept:""};
  $("patName").value=""; $("patEnc").value=""; $("patDept").value="";
  renderStats();
}
$("cardNum").addEventListener("click",()=>toggleCard("num"));
$("cardDen").addEventListener("click",()=>toggleCard("den"));
$("dpClose").addEventListener("click",()=>{ state.detailKind=null; state.detailPage=1; renderStats(); });
$("dpPager").addEventListener("click",e=>{
  const pg=e.target.closest(".pg-btn");
  if(pg && !pg.disabled){ state.detailPage=+pg.dataset.page; renderDetailPanel(); }
});
// 患者明细筛选：按姓名 / encounterId / 科室过滤（实时，重置到第一页）
function applyPatFilter(){
  state.patFilters={ name:$("patName").value, enc:$("patEnc").value, dept:$("patDept").value };
  state.detailPage=1;
  renderDetailPanel();
}
["patName","patEnc"].forEach(id=>{
  $(id).addEventListener("input",applyPatFilter);
});
$("patDept").addEventListener("change",applyPatFilter);
$("patReset").addEventListener("click",()=>{
  state.patFilters={name:"",enc:"",dept:""};
  $("patName").value=""; $("patEnc").value=""; $("patDept").value="";
  state.detailPage=1;
  renderDetailPanel();
});
// 患者明细勾选：标记「数据多了」的患者（分页后仍按 key 记忆）
$("dpBody").addEventListener("change",e=>{
  const cb=e.target.closest(".pat-check");
  if(cb){
    const pk=cb.dataset.kind+":"+cb.dataset.enc;
    if(cb.checked) state.clarifyExtra.add(pk); else state.clarifyExtra.delete(pk);
    updateClarifyExtraCount();
  }
});

$("toCheckBtn").addEventListener("click",openCheck);
$("back1").addEventListener("click",goBasic);   // 返回基础检查（已通过则保留结果）

// 要求澄清：补充缺失数据（数据少了）
// 更新「数据多了」勾选计数
function updateClarifyExtraCount(){
  const n=state.clarifyExtra.size;
  $("clarifyExtraCount").textContent = `已选 ${n} 位患者`;
  $("clearExtraBtn").disabled = n===0;
}

// 清空「数据多了」的已勾选患者（同步刷新计数与明细勾选态）
function clearClarifyExtra(){
  if(state.clarifyExtra.size===0) return;
  state.clarifyExtra=new Set();
  updateClarifyExtraCount();
  renderDetailPanel();
}
$("clearExtraBtn").addEventListener("click",clearClarifyExtra);

// 合并澄清：根据用户的实际选择，分别澄清「数据多了」和「数据少了」
// —— 两类可单独存在，也可同时出现；结果合并为一张卡片，按两类分段展示。
$("clarifyAllBtn").addEventListener("click",()=>{
  const extraKeys=[...state.clarifyExtra];
  const extraNote=($("extraNote")?$("extraNote").value.trim():"");
  const note=$("missNote").value.trim();
  const hasMore = extraKeys.length>0 || extraNote!=="";   // 勾选患者 或 填写说明 任一存在即视为「数据多了」
  const hasLess = note!=="";
  if(!hasMore && !hasLess){
    toast("请先说明「数据多了」的情况（勾选患者或填写说明），或描述「数据少了」的缺失数据");
    return;
  }
  const dr=state.dateRange||"所选日期范围";
  let segs="";

  // 数据多了 段（基于患者明细勾选 和/或 补充说明文本域；两者任一存在即表示存在「数据多了」）
  if(hasMore){
    const noteHtml = extraNote ? `<p class="muted">你的补充说明：${escapeHtml(extraNote)}</p>` : "";
    let headSub;
    if(extraKeys.length>0){
      const sample=extraKeys.slice(0,5).map(k=>k.split(":")[1]).join("、");
      headSub = `为何这些患者被计入（共 ${extraKeys.length} 位，含 ${sample} 等）`;
    } else {
      headSub = `你说明的「数据多了」情况（未勾选具体患者）`;
    }
    segs += `
      <div class="cr-seg">
        <div class="cr-seg-head">
          <span class="cb-tag more">数据多了</span>
          <span class="muted">${headSub}</span>
        </div>
        <div class="cr-seg-body">
          <p>经链路追溯，你标记的问题均指向 <b>源表抽取(s1)</b> 节点：当前口径「${state.caliberName}」在 <code>${dr}</code> 内从业务库抽取了<b>全部</b>就诊记录并落入中间表 <code>src_mz</code>，未对这些患者做排除过滤；下游「概览统计(o1)」「科室统计(d1)」按 <code>dept_id</code> 聚合时将其纳入最终结果，因此出现在统计中。</p>
          <p>若确认这些患者不应计入，可在链路核查页编辑 s1 / o1 节点的 SQL 增加过滤条件，整体执行后对比。</p>
          ${noteHtml}
        </div>
      </div>`;
  }

  // 数据少了 段（基于文本框描述）
  if(note){
    segs += `
      <div class="cr-seg">
        <div class="cr-seg-head">
          <span class="cb-tag miss">数据少了</span>
          <span class="muted">为何该记录未被计入</span>
        </div>
        <div class="cr-seg-body">
          <p>针对你描述的缺失数据，系统核查链路后给出可能原因：</p>
          <ol>
            <li><b>源表抽取(s1)</b>：当前口径「${state.caliberName}」在 <code>${dr}</code> 的时间窗口 / 过滤条件可能未覆盖该记录（如就诊类型、状态未命中抽取规则）。</li>
            <li><b>拓展事件(e1)</b>：该就诊未在事件库中被关联为有效事件，导致未被纳入统计。</li>
            <li><b>科室统计(d1)</b>：该科室编码在映射表中缺失或映射错误，聚合时被丢弃。</li>
          </ol>
          <p class="muted">你描述的缺失数据：${escapeHtml(note)}</p>
          <p>建议进入链路核查，逐节点核对抽取与映射逻辑，定位缺失环节。</p>
        </div>
      </div>`;
  }

  const tag = (hasMore && hasLess) ? "数据多了 + 数据少了"
            : (hasMore ? "数据多了" : "数据少了");
  const html=`
    <div class="clari-result">
      <div class="cr-head">✅ 澄清结果（${tag}）</div>
      <div class="cr-body">${segs}</div>
      <div class="cr-actions">
        <button class="btn sm primary" id="crToCheck">不满意，进入链路核查 ›</button>
        <button class="btn sm" id="crOk">确认无异议</button>
      </div>
    </div>`;
  showClarifyResult(html);
});

// 渲染合并后的澄清结果并接线「继续核查 / 确认」（每次澄清覆盖上一次的合并结果）
function showClarifyResult(html){
  const box=$("clariResult");
  box.innerHTML=html;
  const toCheck=box.querySelector("#crToCheck");
  const ok=box.querySelector("#crOk");
  if(toCheck) toCheck.addEventListener("click",()=>openCheck({fromClarify:true}));
  if(ok) ok.addEventListener("click",()=>{
    box.innerHTML='<div class="clari-result"><div class="cr-done">✓ 已确认，无需进一步排查。</div></div>';
  });
}

// 将第3步「数据确认」里的澄清内容（数据多了 / 数据少了，具体指向）汇总为给 AI 修改 SQL 的自然语言说明
function buildClarifyPrefill(){
  const extraKeys=[...state.clarifyExtra];
  const noteEl=document.getElementById("missNote");
  const note = noteEl ? noteEl.value.trim() : "";
  const parts=["请根据以下在第3步「数据确认」中标记的澄清要求，修改源表抽取(s1)节点的 SQL："];
  const en=document.getElementById("extraNote"); const extraNote=en?en.value.trim():"";
  if(extraKeys.length>0 || extraNote!==""){
    let seg="【数据多了】";
    if(extraKeys.length>0){
      const numList=extraKeys.filter(k=>k.startsWith("num:")).map(k=>k.split(":")[1]);
      const denList=extraKeys.filter(k=>k.startsWith("den:")).map(k=>k.split(":")[1]);
      seg+="以下患者不应被统计（共 "+extraKeys.length+" 位），请在 s1 的抽取 SQL 中增加排除条件：";
      if(numList.length) seg+="\n- 分子侧（"+numList.length+" 位）："+numList.slice(0,10).join("、")+(numList.length>10?" 等":"");
      if(denList.length) seg+="\n- 分母侧（"+denList.length+" 位）："+denList.slice(0,10).join("、")+(denList.length>10?" 等":"");
    } else {
      seg+="请排查并排除不应被统计的数据（未勾选具体患者），请在 s1 的抽取 SQL 中增加排除条件：";
    }
    if(extraNote) seg+="\n- 用户补充说明："+extraNote;
    parts.push(seg);
  }
  if(note){
    parts.push("【数据少了】用户反馈存在未被计入的数据，描述如下：\n\""+note+"\"\n请检查 s1 抽取的时间窗口或过滤条件是否未覆盖上述记录。");
  }
  return parts.join("\n\n");
}

// 基础检查按钮
$("startCheckBtn").addEventListener("click",startBasicCheck);
$("resetCheckBtn").addEventListener("click",resetBasicCheck);
$("toStep3").addEventListener("click",enterDataConfirm);
$("backFromBasic").addEventListener("click",()=>{
  setStep(1); showBaseView("view1");
});
$("backFromBasic2").addEventListener("click",()=>{
  setStep(1); showBaseView("view1");
});

/* =================== 步骤3 核查弹窗 =================== */
const LEGEND=[
  {label:"数据表",color:"var(--primary)"},{label:"源表抽取",color:"var(--warning)"},
  {label:"拓展事件",color:"var(--secondary)"},{label:"概览统计",color:"var(--success)"},
  {label:"科室统计",color:"var(--success)"},{label:"指标结果",color:"var(--primary)"},
];
let flowNodes=[];

function openCheck(opts){
  opts = opts || {};
  setStep(4);
  $("ovTitle").textContent = `数据链路核查 · ${state.indicator}`;
  $("ovCaliber").textContent = state.caliberName;
  $("overlay").classList.add("open");
  flowNodes = buildFlow(state.caliberId);
  const fromClarify = !!opts.fromClarify;
  // 从「澄清结果-不满意」进入时：默认选中源表抽取(s1)节点、激活 AI 修改 SQL tab、带入澄清文字
  state._pendingAiPrefill = fromClarify ? buildClarifyPrefill() : null;
  state.selectedNode = fromClarify ? "s1" : null;
  state.editedSql={}; state.execResult={};
  chainExecuted=false;
  $("resetChainBtn").style.display="none";
  renderFlow();
  renderLegend();
  if(fromClarify){
    const s1 = flowNodes.find(n=>n.id==="s1");
    showNodeDetail(s1);   // 初始 Tab 从 state._pendingAiPrefill 读取 → 激活 AI 修改 SQL 并填充文字
  } else {
    showNodeDetail(null);
  }
}

function renderLegend(){
  $("legend").innerHTML = LEGEND.map(l=>
    `<span class="lg"><span class="dot" style="background:${l.color}"></span>${l.label}</span>`).join("");
}

// 流程节点执行状态徽章（整体执行后）
function execBadgeHtml(n){
  const d = countDiff(ORIGINAL_RESULT[n.id]||[], state.execResult[n.id]||[]);
  return d>0 ? `<span class="exec-badge diff">差异 ${d} 行</span>` : `<span class="exec-badge ok">✓ 已执行</span>`;
}

function renderFlow(){
  const flow=$("flow"); flow.innerHTML="";
  flowNodes.forEach((n,i)=>{
    const node=document.createElement("div");
    node.className="node"+(n.core?" core":"")+(state.selectedNode===n.id?" selected":"");    node.style.borderLeftColor=n.color;
    node.innerHTML=`
      <div class="nt">${n.title}
        ${n.sql? '<span class="sqlico">含 SQL</span>':''}
        ${chainExecuted && n.sql? execBadgeHtml(n):''}
      </div>
      <div class="nd">${n.desc}</div>`;
    node.addEventListener("click",()=>{state.selectedNode=n.id;renderFlow();showNodeDetail(n);});
    flow.appendChild(node);
    if(i<flowNodes.length-1){
      const c=document.createElement("div");c.className="conn";flow.appendChild(c);
    }
  });
}

const TYPE_LABEL={
  TABLE:"数据表",SOURCE_EXTRACT_SQL:"源表抽取 SQL",EXTENDED_EVENT_SQL:"拓展事件 SQL",
  OVERVIEW_SQL:"概览统计 SQL",DEPARTMENT_SQL:"科室统计 SQL",RESULT:"指标结果"
};
const DB_LABEL={BUSINESS:"业务库",SYNC:"同步/ETL",REAL:"真实库",KNOWLEDGE:"知识库"};
function dbLabel(role){return DB_LABEL[role]||role||"";}

// —— AI 修改 SQL：基于自然语言关键词的规则化改写（原型模拟，不接真实大模型）——
function appendCond(sql, cond){
  if(/\bWHERE\b/i.test(sql)){
    return sql.replace(/(\bWHERE\b[^\n]*)/i, `$1\n  AND ${cond}`);
  }
  const gb = sql.match(/(\n\s*(?:GROUP|ORDER)\s+BY[^\n]*)/i);
  if(gb){ return sql.replace(gb[0], `\nWHERE ${cond}${gb[0]}`); }
  return sql + `\nWHERE ${cond}`;
}

function aiGenerateSql(node, prompt){
  let sql = node.sql;
  const notes = [];
  let hit = false;

  const excludeEmergency = /排除急诊|不含急诊|不包括急诊|去掉急诊|剔除急诊|排除急诊科|不含急诊科/.test(prompt);
  const recent30 = /30\s*天|最近\s*30|近\s*30/.test(prompt.toLowerCase());
  const recent7 = /7\s*天|最近\s*7|近\s*7|一周|7天/.test(prompt.toLowerCase());
  const outpatientOnly = /仅门诊|只要门诊|只统计门诊|仅统计门诊|只算门诊/.test(prompt);

  if(excludeEmergency){
    sql = appendCond(sql, "dept_id <> '急诊科'");
    notes.push("已加入『排除急诊科』过滤条件");
    hit = true;
  }
  if(recent30){
    sql = appendCond(sql, "visit_date >= CURDATE() - INTERVAL 30 DAY");
    notes.push("已按『最近 30 天』改写时间范围");
    hit = true;
  } else if(recent7){
    sql = appendCond(sql, "visit_date >= CURDATE() - INTERVAL 7 DAY");
    notes.push("已按『最近 7 天』改写时间范围");
    hit = true;
  }
  if(outpatientOnly){
    sql = appendCond(sql, "visit_type = '门诊'");
    notes.push("已限定『仅门诊就诊』");
    hit = true;
  }
  if(!hit){
    notes.push("未识别到明确的修改意图，已保留原 SQL。可尝试：排除急诊科 / 改为最近30天 / 仅统计门诊");
  }
  return { sql, notes, hit };
}

// —— SQL 安全 / 语法校验（原型模拟，不接真实校验服务）——
// 返回 { ok, errors:[严重/阻断], warnings:[提醒] }
function validateSql(raw){
  const s=(raw||"").replace(/\s+$/,"").replace(/;\s*$/,"").trim();
  const errors=[], warnings=[];
  if(!s){ errors.push("SQL 为空，请输入查询语句"); return {ok:false,errors,warnings}; }

  // 1) 括号平衡
  let bal=0; for(const c of s){ if(c==="(")bal++; else if(c===")")bal--; if(bal<0)break; }
  if(bal!==0) errors.push("括号不匹配：左括号与右括号数量不一致");

  // 2) 单引号闭合
  const q=(s.match(/'/g)||[]).length;
  if(q%2!==0) errors.push("单引号未闭合：字符串字面量缺少结束引号");

  // 3) 语句起始关键字
  const kw=(s.replace(/^\(/,"").match(/^[a-zA-Z]+/)||[""])[0].toUpperCase();
  const readOnly=["SELECT","WITH","SHOW","EXPLAIN","DESC","DESCRIBE"];
  const writeKw=["INSERT","UPDATE","DELETE","DROP","ALTER","TRUNCATE","CREATE","GRANT","REVOKE","MERGE","REPLACE"];
  if(!readOnly.concat(writeKw).includes(kw))
    errors.push(`语句非法：应以 SELECT / WITH 等关键字开头，当前开头为「${kw}」`);

  // 4) 破坏性 / 写操作（安全检查·阻断）
  if(/\b(drop\s+(table|database|view|index)|truncate\s+table|alter\s+(table|database)|grant\s+|revoke\s+|create\s+(table|database|view|index))\b/i.test(s))
    errors.push("检测到高危写/DDL 操作（DROP / TRUNCATE / ALTER / GRANT 等），存在数据破坏风险");
  if(/\b(insert\s+into|update\s+[\w`"]+\s+set|delete\s+from)\b/i.test(s))
    errors.push("检测到数据变更语句（INSERT / UPDATE / DELETE），只读节点不允许修改数据");
  if(/\b(into\s+(outfile|dumpfile))\b/i.test(s))
    errors.push("检测到 INTO OUTFILE / DUMPFILE，存在文件写出安全风险");

  // 5) 多语句（分号分隔 → 注入/越权风险）
  const semicolons=(s.match(/;/g)||[]).length;
  if(semicolons>0) errors.push("检测到语句结束符（分号），整体执行按节点单条运行，请勿在 SQL 中拼接多条语句");

  // 6) 注释（可能绕过条件）
  if(/(--|\/\*|\*\/)/.test(s)) warnings.push("检测到 SQL 注释（-- 或 /* */），可能被用于绕过过滤条件，请确认是否为预期");

  // 7) 疑似注入/探测
  if(/\b(union\s+(all\s+)?select)\b/i.test(s)) warnings.push("检测到 UNION SELECT，可能被用于联合查询注入，请确认数据来源可信");
  if(/\b(sleep\s*\(|benchmark\s*\(|waitfor\s+delay|xp_cmdshell|information_schema)\b/i.test(s))
    warnings.push("检测到疑似注入/探测函数（SLEEP / BENCHMARK / xp_cmdshell 等），请核查");

  // 8) SELECT 缺 FROM（常量表达式除外）
  if(kw==="SELECT" && !/\bfrom\b/i.test(s) && !/^select\s+[\w\.\(\*\)\s,]+$/i.test(s))
    warnings.push("SELECT 未检测到 FROM 子句，请确认是否为预期的常量/函数表达式");

  return {ok:errors.length===0, errors, warnings};
}

// 渲染校验结果到指定容器
function renderValidateResult(r){
  if(r.ok){
    const w = r.warnings.length
      ? `<ul class="vwarn">${r.warnings.map(w=>`<li>⚠ ${escapeHtml(w)}</li>`).join("")}</ul>` : "";
    return `<div class="vresult pass"><span class="vicon">✓</span><div>
      <b>校验通过</b>
      <div class="vsub">未检测到语法错误或安全风险${r.warnings.length?`，但有 ${r.warnings.length} 条提醒`:""}。</div>
      ${w}</div></div>`;
  }
  const e = `<ul class="verr">${r.errors.map(e=>`<li>✗ ${escapeHtml(e)}</li>`).join("")}</ul>`;
  const w = r.warnings.length
    ? `<ul class="vwarn">${r.warnings.map(w=>`<li>⚠ ${escapeHtml(w)}</li>`).join("")}</ul>` : "";
  return `<div class="vresult fail"><span class="vicon">✗</span><div>
    <b>校验未通过（${r.errors.length} 处错误${r.warnings.length?`，${r.warnings.length} 条提醒`:""}）</b>
    ${e}${w}</div></div>`;
}

function showNodeDetail(n, opts){
  const body=$("dBody");
  if(!n){
    if(chainExecuted){ showNodeSummary(); }
    else { body.innerHTML='<div class="empty">点击左侧流程图中的节点查看详情</div>'; }
    $("dTitle").textContent="节点详情";
    return;
  }
  $("dTitle").textContent=n.title;
  let html=`
    <div class="kv"><div class="k">节点类型</div><div class="v"><span class="chip">${TYPE_LABEL[n.type]||n.type}</span></div></div>
    ${n.dbRole?`<div class="kv"><div class="k">数据库角色</div><div class="v">${dbLabel(n.dbRole)}</div></div>`:""}
    ${n.desc?`<div class="kv"><div class="k">描述</div><div class="v">${n.desc}</div></div>`:""}
    ${n.tables&&n.tables.length?`<div class="kv"><div class="k">关联表</div><div class="v">${n.tables.map(t=>`<span class="chip">${t}</span>`).join("")}</div></div>`:""}
  `;
  if(n.sql){
    const cur = state.editedSql[n.id] ?? n.sql;
    let reExtractHtml = "";
    if(n.sqlKind==="源表抽取"){
      reExtractHtml = `
        <div class="kv">
          <div class="k">数据抽取操作</div>
          <div class="re-extract-box">
            <p class="muted" style="font-size:12.5px;margin-bottom:8px;">当前抽取自业务库 <b>mz_jz</b>，落至中间表 <b>src_mz</b>。如源头数据已更新，可重新抽取以刷新中间表。</p>
            <div style="display:flex;gap:8px;align-items:center;">
              <button class="btn sm" id="reExtractBtn">🔄 重新抽取</button>
              <span class="muted" id="reExtractStat" style="font-size:12px;"></span>
            </div>
            <div id="reExtractArea"></div>
          </div>
        </div>`;
    }
    html+= reExtractHtml + `
      <div class="kv">
        <div class="k">SQL 文本（可编辑）</div>
        <pre class="sql">${escapeHtml(n.sql)}</pre>
      </div>
      <div class="kv">
        <div class="edt-tabs">
          <div class="edt-tab active" data-tab="manual">✎ 直接编辑 SQL</div>
          <div class="edt-tab" data-tab="ai">🤖 AI 修改 SQL</div>
        </div>
        <div class="edt-panel" id="panelManual">
          <textarea class="sql-edit" id="sqlEdit" data-node="${n.id}">${escapeHtml(cur)}</textarea>
          <div style="margin-top:8px;display:flex;gap:8px;align-items:center;">
            <button class="btn sm" id="validateBtn">🔍 校验 SQL</button>
            <button class="btn sm" id="resetBtn">重置</button>
            <span class="muted" style="font-size:12px;">编辑 SQL 后点右上角「整体执行」按完整链路运行并对比</span>
          </div>
          <div id="validateArea" class="validate-area"></div>
        </div>
        <div class="edt-panel" id="panelAi" style="display:none;">
          <p class="hint" style="margin-bottom:8px;">用自然语言描述你想要的修改，AI 将生成新的 SQL（原型模拟）。可尝试：<b>排除急诊科</b>、<b>改为最近 30 天</b>、<b>仅统计门诊</b>。</p>
          <textarea class="ai-prompt" id="aiPrompt" placeholder="例如：把汇总范围改为最近 30 天，并排除急诊科数据"></textarea>
          <div style="margin-top:8px;display:flex;gap:8px;align-items:center;">
            <button class="btn primary sm" id="aiGenBtn">✨ 生成 SQL</button>
            <span class="muted" style="font-size:12px;" id="aiHint"></span>
          </div>
          <div id="aiResult" class="ai-result" style="display:none;"></div>
        </div>
        <div id="execArea"></div>
      </div>`;
  }else{
    html+=`<div class="kv"><div class="v muted" style="font-size:12.5px;">该节点为数据表 / 结果节点，无 SQL。</div></div>`;
  }
  body.innerHTML=html;

  if(n.sql){
    const ta=$("sqlEdit");
    ta.addEventListener("input",()=>{state.editedSql[n.id]=ta.value;});
    $("resetBtn").addEventListener("click",()=>{
      state.editedSql[n.id]=n.sql; ta.value=n.sql;
      if(chainExecuted){ renderNodeComparison(n); } else { $("execArea").innerHTML=""; }
      $("validateArea").innerHTML="";
      toast("已重置为原始 SQL");
    });

    // 校验 SQL：防止语法错误或安全风险
    $("validateBtn").addEventListener("click",()=>{
      const sql=ta.value;
      const btn=$("validateBtn"), area=$("validateArea");
      btn.disabled=true; btn.textContent="校验中…";
      area.innerHTML='<div class="vrow"><div class="spin"></div><span class="muted" style="font-size:12px;">正在校验 SQL 语法与安全性…</span></div>';
      setTimeout(()=>{
        const r=validateSql(sql);
        btn.disabled=false; btn.textContent="🔍 校验 SQL";
        area.innerHTML=renderValidateResult(r);
        toast(r.ok?"SQL 校验通过":"SQL 校验未通过，请修正后重试");
      },600);
    });

    // Tab 切换：直接编辑 / AI 修改
    document.querySelectorAll(".edt-tab").forEach(tab=>{
      tab.addEventListener("click",()=>{
        document.querySelectorAll(".edt-tab").forEach(t=>t.classList.remove("active"));
        tab.classList.add("active");
        const which=tab.dataset.tab;
        $("panelManual").style.display = which==="manual"?"block":"none";
        $("panelAi").style.display = which==="ai"?"block":"none";
        if(which==="manual"){ ta.value = state.editedSql[n.id] ?? n.sql; }
      });
    });

    // 初始 Tab（支持从澄清带入 AI 修改：默认激活 AI tab 并填充文字说明）
    const initTab = (opts && opts.defaultTab) || (state._pendingAiPrefill ? "ai" : "manual");
    const prefill = (opts && opts.aiPrefill) || state._pendingAiPrefill || "";
    state._pendingAiPrefill = null;
    document.querySelectorAll(".edt-tab").forEach(t=>t.classList.toggle("active", t.dataset.tab===initTab));
    $("panelManual").style.display = initTab==="manual" ? "block":"none";
    $("panelAi").style.display = initTab==="ai" ? "block":"none";
    if(initTab==="ai" && prefill){ $("aiPrompt").value = prefill; }

    // AI 修改：自然语言生成 SQL
    $("aiGenBtn").addEventListener("click",()=>{
      const prompt=$("aiPrompt").value.trim();
      if(!prompt){ $("aiHint").textContent="请先输入修改描述"; return; }
      const { sql, notes, hit } = aiGenerateSql(n, prompt);
      const box=$("aiResult");
      box.style.display="block";
      box.innerHTML=`
        <div class="ar-head">✨ AI 生成的 SQL ${hit?"（已识别修改意图）":"（未识别意图，已保留原 SQL）"}</div>
        <pre class="ar-sql">${escapeHtml(sql)}</pre>
        <div class="ar-notes">${notes.map(t=>`• ${t}`).join("<br/>")}</div>
        <div class="ar-actions">
          <button class="btn primary sm" id="aiApplyBtn">应用到编辑器</button>
          <button class="btn sm" id="aiApplyExecBtn">应用</button>
          <button class="btn sm" id="aiValidateBtn">🔍 校验 SQL</button>
        </div>
        <div id="aiValidateArea" class="validate-area"></div>`;
      $("aiApplyBtn").addEventListener("click",()=>{
        state.editedSql[n.id]=sql; ta.value=sql; $("execArea").innerHTML="";
        document.querySelectorAll(".edt-tab").forEach(t=>t.classList.remove("active"));
        document.querySelector('.edt-tab[data-tab="manual"]').classList.add("active");
        $("panelManual").style.display="block"; $("panelAi").style.display="none";
        toast("已将 AI 生成的 SQL 应用到编辑器");
      });
      $("aiApplyExecBtn").addEventListener("click",()=>{
        state.editedSql[n.id]=sql; ta.value=sql;
        document.querySelectorAll(".edt-tab").forEach(t=>t.classList.remove("active"));
        document.querySelector('.edt-tab[data-tab="manual"]').classList.add("active");
        $("panelManual").style.display="block"; $("panelAi").style.display="none";
        toast("已应用 AI 生成的 SQL，请点「整体执行」查看对比");
      });
      $("aiValidateBtn").addEventListener("click",()=>{
        const btn=$("aiValidateBtn"), area=$("aiValidateArea");
        btn.disabled=true; btn.textContent="校验中…";
        area.innerHTML='<div class="vrow"><div class="spin"></div><span class="muted" style="font-size:12px;">正在校验 AI 生成 SQL…</span></div>';
        setTimeout(()=>{
          const r=validateSql(sql);
          btn.disabled=false; btn.textContent="🔍 校验 SQL";
          area.innerHTML=renderValidateResult(r);
          toast(r.ok?"AI 生成 SQL 校验通过":"AI 生成 SQL 校验未通过");
        },600);
      });
    });

    // 源表抽取节点：重新抽取
    if(n.sqlKind==="源表抽取"){
      $("reExtractBtn").addEventListener("click",()=>{
        const btn=$("reExtractBtn"), stat=$("reExtractStat"), area=$("reExtractArea");
        btn.disabled=true; stat.textContent="正在从业务库重新抽取…";
        area.innerHTML='<div class="spin"></div>';
        setTimeout(()=>{
          btn.disabled=false; stat.textContent="✅ 重新抽取完成";
          const rows = ORIGINAL_RESULT.s1 || [];
          const total = rows.reduce((s,r)=> s + (parseInt(String(r[2]).replace(/[^\d]/g,""),10)||0), 0);
          const ts = new Date().toLocaleString("zh-CN",{hour12:false});
          area.innerHTML = `
            <div class="re-extract-result">
              <div class="rr-row"><span>抽取来源</span><b>mz_jz（业务库）</b></div>
              <div class="rr-row"><span>落表</span><b>src_mz（中间表）</b></div>
              <div class="rr-row"><span>抽取记录数</span><b>${total.toLocaleString()} 行</b></div>
              <div class="rr-row"><span>耗时</span><b>约 ${(0.8+Math.random()*0.6).toFixed(2)} 秒</b></div>
              <div class="rr-row"><span>完成时间</span><b>${ts}</b></div>
              <div class="rr-tip">中间表 src_mz 已刷新，下游节点（拓展事件 / 概览统计 / 科室统计）可基于新数据重新计算。</div>
            </div>`;
        }, 1000);
      });
    }

    // 整体执行后，自动渲染该节点原结果 / 新结果对比
    if(chainExecuted && state.execResult[n.id]){
      renderNodeComparison(n);
    } else {
      $("execArea").innerHTML = '<div class="exec-hint-box">该节点尚未执行。请点击右上角「整体执行」，按完整链路运行所有节点后，在此查看原结果 / 新结果对比。</div>';
    }
  }
}

const PAGE_SIZE = 5;          // 演示用分页大小（原型模拟，真实场景可按需调整）
let currentExec = null;       // 当前执行结果上下文，供分页复用

// 渲染某节点的原/新对比（由整体执行结果驱动）
function renderNodeComparison(n){
  const orig = ORIGINAL_RESULT[n.id] || [];
  const result = state.execResult[n.id] || [];
  const isDiff = (n.id==="o1"||n.id==="d1");
  currentExec = { orig, result, isDiff, page:0, pageSize:PAGE_SIZE, nodeId:n.id };
  renderExecPage();
}

// 整体执行：按完整链路顺序运行所有 SQL 节点（不允许单个节点单独执行）
function runWholeChain(){
  const btn=$("runChainBtn");
  btn.disabled=true; btn.textContent="⏳ 正在按链路执行…";
  setTimeout(()=>{
    flowNodes.forEach(n=>{ if(n.sql){ state.execResult[n.id]=simulateExec(n.id, state.editedSql[n.id]); } });
    chainExecuted=true;
    btn.disabled=false; btn.textContent="▶ 整体执行";
    $("resetChainBtn").style.display="";
    renderFlow();
    if(state.selectedNode){ showNodeDetail(flowNodes.find(x=>x.id===state.selectedNode)); }
    else { showNodeSummary(); }
    toast("链路整体执行完成，已生成各节点对比");
  }, 1000);
}

// 执行结果总览（未选中具体节点时展示，清晰呈现整条链路的差异分布）
function showNodeSummary(){
  const body=$("dBody");
  const sqlNodes = flowNodes.filter(n=>n.sql);
  const totalDiff = sqlNodes.reduce((s,n)=> s + countDiff(ORIGINAL_RESULT[n.id]||[], state.execResult[n.id]||[]), 0);
  const changed = sqlNodes.filter(n=> countDiff(ORIGINAL_RESULT[n.id]||[], state.execResult[n.id]||[]) > 0).length;
  const rows = sqlNodes.map(n=>{
    const d = countDiff(ORIGINAL_RESULT[n.id]||[], state.execResult[n.id]||[]);
    const badge = d>0 ? `<span class="exec-badge diff">差异 ${d} 行</span>` : `<span class="exec-badge ok">✓ 一致</span>`;
    return `<div class="es-row" data-node="${n.id}">
        <div class="es-name">${n.core?'<span class="core-dot"></span>':''}${n.title}</div>
        ${badge}
        <span class="es-go">查看对比 ›</span>
      </div>`;
  }).join("");
  body.innerHTML = `
    <div class="es-head">
      <div class="es-title">执行结果总览</div>
      <div class="es-stat">共 ${sqlNodes.length} 个 SQL 节点 · <b style="color:var(--warning)">${changed}</b> 个存在差异 · 累计差异 <b>${totalDiff}</b> 行</div>
      <div class="hint" style="margin-top:6px;">（如已修改某节点 SQL，请重新点击「整体执行」刷新对比）</div>
    </div>
    <div class="es-list">${rows}</div>`;
  body.querySelectorAll(".es-row").forEach(r=>{
    r.addEventListener("click",()=>{
      const n = flowNodes.find(x=>x.id===r.dataset.node);
      state.selectedNode=n.id; renderFlow(); showNodeDetail(n);
    });
  });
}

// 重置整体执行结果
function resetChain(){
  chainExecuted=false; state.execResult={};
  $("resetChainBtn").style.display="none";
  renderFlow();
  showNodeDetail(state.selectedNode ? flowNodes.find(n=>n.id===state.selectedNode) : null);
}

function renderExecPage(){
  if(!currentExec) return;
  const { orig, result, isDiff, pageSize } = currentExec;
  const total = Math.max(orig.length, result.length);
  const pages = Math.max(1, Math.ceil(total/pageSize));
  let cur = Math.min(currentExec.page, pages-1);
  currentExec.page = cur;
  const start = cur*pageSize, end = Math.min(start+pageSize, total);

  const head = orig[0]? orig[0].slice(0,-1).map(h=>`<th>${h}</th>`).join("")+`<th>数值</th>`:"";
  const showHead = isDiff ? `<thead><tr>${head}</tr></thead>` : "";

  let oRows="", nRows="";
  for(let i=start;i<end;i++){
    const o=orig[i]||[], ne=result[i]||[];
    if(isDiff){
      let cls="",delta="";
      if(o.length && ne.length){
        const ov=+o[o.length-1], nv=+ne[ne.length-1];
        if(ov!==nv){ cls = nv>ov?"cell-up":"cell-pos"; delta = (nv>ov?"+":"")+(nv-ov); }
      }
      const oCells=o.map(c=>`<td>${c}</td>`).join("");
      const nCells=ne.map((c,ci)=> ci===ne.length-1 && cls ? `<td class="${cls}">${c} <small>(${delta})</small></td>` : `<td>${c}</td>`).join("");
      oRows+=`<tr>${oCells}</tr>`;
      nRows+=`<tr>${nCells}</tr>`;
    }else{
      oRows+=`<tr>${o.map(c=>`<td>${c}</td>`).join("")}</tr>`;
      nRows+=`<tr>${ne.map(c=>`<td>${c}</td>`).join("")}</tr>`;
    }
  }

  const area=$("execArea");
  const diffCount = isDiff ? countDiff(orig,result) : 0;
  const conclusion = isDiff
    ? `✅ 编辑后共 <b>${diffCount}</b> 行数值变化，与数据确认环节发现的「缺失 / 增多」吻合 —— 疑似该节点 SQL 的筛选 / 聚合逻辑导致了统计偏差，可据此修正口径 SQL。`
    : `✅ SQL 已重新执行，结果已刷新（原型模拟，未连接真实数据库）。`;
  const pager = pages>1 ? `
    <div class="pager">
      <button class="btn sm" id="pgPrev" ${cur===0?"disabled":""}>‹ 上一页</button>
      <span class="pg-info">第 ${cur+1} / ${pages} 页 · 共 ${total} 行</span>
      <button class="btn sm" id="pgNext" ${cur>=pages-1?"disabled":""}>下一页 ›</button>
    </div>` : "";

  area.innerHTML=`
    <div class="cmp-legend">左侧 <b>原结果</b>（编辑前，灰）　·　右侧 <b>新结果</b>（执行后，蓝，差异行高亮）</div>
    <div class="compare">
      <div class="ctable orig">
        <div class="ctitle">原结果（编辑前）</div>
        <div class="table-wrap"><table>${showHead}<tbody>${oRows||'<tr><td class="empty">无数据</td></tr>'}</tbody></table></div>
      </div>
      <div class="ctable new">
        <div class="ctitle">新结果（执行后）</div>
        <div class="table-wrap"><table>${showHead}<tbody>${nRows||'<tr><td class="empty">无数据</td></tr>'}</tbody></table></div>
      </div>
    </div>
    ${pager}
    <div class="conclusion">${conclusion}</div>
  `;
  if($("pgPrev")) $("pgPrev").addEventListener("click",()=>{ currentExec.page=Math.max(0,currentExec.page-1); renderExecPage(); });
  if($("pgNext")) $("pgNext").addEventListener("click",()=>{ currentExec.page=Math.min(pages-1,currentExec.page+1); renderExecPage(); });
}

function countDiff(a,b){
  let c=0;const n=Math.max(a.length,b.length);
  for(let i=0;i<n;i++){
    const x=a[i],y=b[i];
    if(x&&y&&(+x[x.length-1]!==+y[y.length-1])) c++;
  }
  return c;
}

$("dClose").addEventListener("click",()=>{state.selectedNode=null;renderFlow();showNodeDetail(null);});
$("closeOv").addEventListener("click",()=>{
  $("overlay").classList.remove("open");
  showBaseView(lastBaseView); setStep(VIEW_STEP[lastBaseView]);
});
$("runChainBtn").addEventListener("click",runWholeChain);
$("resetChainBtn").addEventListener("click",resetChain);

// 一键导出：各节点 原 SQL / 新 SQL
$("exportSqlBtn").addEventListener("click",exportAllSql);
function exportAllSql(){
  const lines=[];
  lines.push("指标助手 · 数据链路核查 SQL 导出");
  lines.push("指标：" + state.indicator);
  lines.push("口径：" + state.caliberName);
  lines.push("导出时间：" + new Date().toLocaleString("zh-CN"));
  lines.push("");
  flowNodes.forEach(n=>{
    if(!n.sql) return;
    const orig = n.sql;
    const edited = state.editedSql[n.id] ?? n.sql;
    const editedTag = state.editedSql[n.id] ? "（已编辑）" : "（未编辑，与原 SQL 一致）";
    lines.push("========================================");
    lines.push("节点：" + n.title);
    lines.push("类型：" + (TYPE_LABEL[n.type]||n.type) + (n.core?"（核心口径）":""));
    lines.push("----------------------------------------");
    lines.push("【原 SQL】");
    lines.push(orig);
    lines.push("");
    lines.push("【新 SQL】" + editedTag);
    lines.push(edited);
    lines.push("");
  });
  lines.push("========================================");
  const content = lines.join("\n");
  const blob = new Blob([content],{type:"text/plain;charset=utf-8"});
  const url = URL.createObjectURL(blob);
  const a=document.createElement("a");
  a.href=url;
  a.download = `SQL导出_${state.indicator}_${state.caliberName}.txt`;
  document.body.appendChild(a); a.click(); a.remove();
  URL.revokeObjectURL(url);
  toast("已导出各节点原 SQL / 新 SQL");
}

/* =================== 通用 =================== */
// 基础视图（步骤1/2/3 对应的页面）切换；步骤4 为全屏核查弹窗，不在此列
const VIEW_STEP={view1:1,viewBasic:2,view2:3};
let lastBaseView="view1";
function showBaseView(name){
  ["view1","view2","viewBasic"].forEach(id=>{ if(id!==name) $(id).style.display="none"; });
  $(name).style.display="block";
  lastBaseView=name;
}
// 第 1 步是否已完成（已选口径并查询）
function step1Done(){ return !!state.caliberId; }

// 步骤条直达：第 1 步必选完成后，其余步骤可点击跳过直达
function jumpToStep(n){
  $("overlay").classList.remove("open");   // 离开核查弹窗时先收起（n===4 会重新打开）
  if(n===1){ setStep(1); showBaseView("view1"); return; }
  if(!step1Done()){ toast("请先完成第 1 步：选择指标与口径"); return; }
  if(n===4){ openCheck(); return; }   // 链路核查为弹窗，直接打开
  // 进入基础检查 / 数据确认（隐藏全部基础视图，再由各自入口显示）
  $("view1").style.display="none"; $("view2").style.display="none"; $("viewBasic").style.display="none";
  if(n===2) goBasic();
  if(n===3) enterDataConfirm();
}
function refreshStepper(){
  const done=step1Done();
  document.querySelectorAll("#stepper .step").forEach(s=>{
    const sn=+s.dataset.step;
    s.classList.toggle("locked", sn>1 && !done);
  });
  $("stepTip").innerHTML = done
    ? '第 1 步已完成：步骤 2 / 3 / 4 均可点击步骤条<b>直接跳过直达</b>，无需依次经过。'
    : '第 1 步为<b>必选</b>：完成指标与口径选择后，步骤 2 / 3 / 4 才能点击直达。';
}
document.querySelectorAll("#stepper .step").forEach(s=>{
  s.addEventListener("click",()=>{
    const sn=+s.dataset.step;
    if(sn===1 || step1Done()) jumpToStep(sn);
    else toast("请先完成第 1 步：选择指标与口径");
  });
});

function setStep(n){
  document.querySelectorAll(".step").forEach(s=>{
    const sn=+s.dataset.step;
    s.classList.toggle("active",sn===n);
    s.classList.toggle("done",sn<n);
  });
}
function escapeHtml(s){return s.replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;");}
let toastTimer;
function toast(msg){
  const t=$("toast");t.textContent=msg;t.classList.add("show");
  clearTimeout(toastTimer);toastTimer=setTimeout(()=>t.classList.remove("show"),1800);
}

// 初始化：第 1 步未完成前，步骤 2/3/4 锁定
refreshStepper();
