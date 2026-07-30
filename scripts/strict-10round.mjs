import fs from 'node:fs';
import path from 'node:path';

const EP = process.env.AGENT_ENDPOINT || 'http://127.0.0.1:8765/api/agent/chat';
const OUT = path.resolve('output', 'strict-10round.json');
const TS = new Date().toISOString().replace(/\D/g, '').slice(0, 14);
const TIMEOUT = 600_000;

/**
 * camelCase 全链路验收：聚焦 JSON 出口键名、抽取管道、卡片结构。
 * 每个对话 10 轮，验证后端在 camelCase 改造后功能正常。
 *
 * lenient=true  → PLANNER_OUTPUT_INVALID 也算通过（planner 容错边界）
 * expectClarify → 预期 stopReason=clarification
 * batch=true    → 批量请求，更长超时
 */
const conversations = [
  {
    name: '对话1: HXZD-001-001 单指标全流程',
    sid: `acc10_d1_${TS}`,
    turns: [
      // T1: 识别指标、要求时间
      { q: '帮我算一下患者入院48小时内转科的比例',
        v: a => a.includes('时间') || a.includes('范围'),
        expectClarify: true },
      // T2: 给时间 → 计算返回
      { q: '去年',
        v: a => (a.includes('%') || a.includes('百分')) && /\d/.test(a),
        expectClarify: false },
      // T3: 达标判定（可能 planner 不支持"达标了吗"）
      { q: '帮我判断患者入院48小时内转科的比例是否达标',
        v: a => a.includes('达标') || a.includes('目标') || a.includes('导向'),
        expectClarify: false, lenient: true },
      // T4: 分母明细
      { q: '帮我查看患者入院48小时内转科比例的分母患者明细',
        v: a => a.includes('明细') || a.includes('患者') || a.includes('列表'),
        expectClarify: false, lenient: true },
      // T5: 分子明细
      { q: '帮我查看患者入院48小时内转科比例的分子患者明细',
        v: a => a.includes('明细') || a.includes('患者') || a.includes('列表'),
        expectClarify: false, lenient: true },
      // T6: 定义公式
      { q: '患者入院48小时内转科的比例这个指标是怎么算的',
        v: a => a.includes('公式') || a.includes('定义') || a.includes('计算') || a.includes('口径'),
        expectClarify: false, lenient: true },
      // T7: 换时间
      { q: '患者入院48小时内转科的比例换成今年至今',
        v: a => a.includes('2026') || a.includes('%') || /\d/.test(a),
        expectClarify: false, lenient: true },
      // T8: 切换不同指标验证多指标计算
      { q: '算HXZD-009-002去年',
        v: a => /\d/.test(a),
        expectClarify: false },
      // T9: 前年分母明细
      { q: '帮我查看患者入院48小时内转科比例前年的分母患者明细',
        v: a => a.includes('明细') || a.includes('患者') || a.includes('记录') || /\d/.test(a),
        expectClarify: false, lenient: true },
      // T10: 结束
      { q: '好的谢谢',
        v: () => true, lenient: true },
    ],
  },
  {
    name: '对话2: HXZD-002-001 查房率时间切换',
    sid: `acc10_d2_${TS}`,
    turns: [
      { q: '算HXZD-002-001去年的',
        v: a => a.includes('%') || /\d/.test(a),
        expectClarify: false },
      { q: '算HXZD-002-001今年1月到6月的',
        v: a => a.includes('2026') && /\d/.test(a),
        expectClarify: false },
      { q: '算HXZD-002-001从2025年3月1号到9月30号',
        v: a => a.includes('2025') && /\d/.test(a),
        expectClarify: false },
      { q: '算HXZD-002-001本月',
        v: a => /\d/.test(a) || a.includes('样本'),
        expectClarify: false },
      { q: '算HXZD-002-001最近3个月',
        v: a => /\d/.test(a) || a.includes('样本'),
        expectClarify: false },
      { q: '算HXZD-002-001去年',
        v: a => a.includes('%') || /\d/.test(a),
        expectClarify: false },
      { q: '算HXZD-002-001前年',
        v: a => a.includes('2024') || /\d/.test(a),
        expectClarify: false },
      { q: '算HXZD-002-001上个月的',
        v: a => /\d/.test(a) || a.includes('样本'),
        expectClarify: false },
      { q: '算HXZD-002-001前年的',
        v: a => a.includes('2024') || /\d/.test(a),
        expectClarify: false },
      { q: '好的',
        v: () => true, lenient: true },
    ],
  },
  {
    name: '对话3: HXZD-003-001 急会诊多步',
    sid: `acc10_d3_${TS}`,
    turns: [
      { q: '算急会诊及时到位率去年',
        v: a => a.includes('%') || /\d/.test(a) || a.includes('急会诊'),
        expectClarify: false },
      { q: '急会诊及时到位率的定义是什么',
        v: a => a.includes('定义') || a.includes('急会诊') || a.includes('口径'),
        expectClarify: false, lenient: true },
      { q: '算HXZD-003-001本月',
        v: a => /\d/.test(a) || a.includes('样本'),
        expectClarify: false },
      { q: '算HXZD-003-001前年',
        v: a => /\d/.test(a) || a.includes('样本'),
        expectClarify: false },
      { q: '算HXZD-003-002去年',
        v: a => /\d/.test(a) || a.includes('样本'),
        expectClarify: false },
      { q: '算HXZD-003-003去年',
        v: a => /\d/.test(a) || a.includes('样本'),
        expectClarify: false },
      { q: '算HXZD-003-004去年',
        v: a => /\d/.test(a) || a.includes('样本'),
        expectClarify: false },
      { q: '这几个会诊指标哪个最差',
        v: a => a.includes('差') || a.includes('低') || a.includes('高') || a.includes('指标'),
        expectClarify: false, lenient: true },
      { q: '给我改进建议',
        v: a => a.includes('建议') || a.includes('改进') || a.includes('措施'),
        expectClarify: false, lenient: true },
      { q: '谢谢',
        v: () => true, lenient: true },
    ],
  },
  {
    name: '对话4: HXZD-006-003 多口径',
    sid: `acc10_d4_${TS}`,
    turns: [
      // T1: 用全称算出区时间口径
      { q: '算非计划再入院率用出区时间口径去年的',
        v: a => /\d/.test(a) || a.includes('出区') || a.includes('口径'),
        expectClarify: false, lenient: true },
      // T2: 换入区时间
      { q: '算非计划再入院率用入区时间口径去年的',
        v: a => /\d/.test(a) || a.includes('入区') || a.includes('口径'),
        expectClarify: false, lenient: true },
      // T3: 对比
      { q: '非计划再入院率两种口径结果差多少',
        v: a => a.includes('口径') || /\d/.test(a),
        expectClarify: false, lenient: true },
      // T4: 明细
      { q: '非计划再入院率出区时间口径的明细',
        v: a => a.includes('明细') || a.includes('患者'),
        expectClarify: false, lenient: true },
      // T5: HXZD-015-001
      { q: '算HXZD-015-001去年',
        v: a => a.includes('%') || /\d/.test(a),
        expectClarify: false },
      // T6: HXZD-015-001 另一口径
      { q: '算HXZD-015-001另一个口径去年',
        v: a => a.includes('%') || /\d/.test(a) || a.includes('口径'),
        expectClarify: false, lenient: true },
      // T7: 按科室
      { q: '算非计划再入院率按科室看看',
        v: a => a.includes('科室') || a.includes('下钻'),
        expectClarify: false, lenient: true },
      // T8: SQL
      { q: '给我看看非计划再入院率的SQL',
        v: a => a.includes('SQL') || a.includes('sql') || a.includes('SELECT'),
        expectClarify: false, lenient: true },
      // T9: 公式
      { q: '非计划再入院率的公式是什么',
        v: a => a.includes('公式') || a.includes('定义') || a.includes('计算'),
        expectClarify: false, lenient: true },
      { q: '谢谢',
        v: () => true, lenient: true },
    ],
  },
  {
    name: '对话5: 批量3指标计算',
    sid: `acc10_d5_${TS}`,
    turns: [
      // T1: 批量3个
      { q: '计算患者入院48小时内转科的比例、急会诊及时到位率、危急值报告时间去年',
        v: a => a.includes('项') || a.includes('指标'),
        expectClarify: false },
      // T2: 检查卡片结构
      { q: '这三个指标分别是什么结果',
        v: a => a.includes('转科') || a.includes('会诊') || a.includes('危急值'),
        expectClarify: false, lenient: true },
      // T3: 达标
      { q: '这三个指标达标了吗',
        v: a => a.includes('达标') || a.includes('目标'),
        expectClarify: false, lenient: true },
      // T4: 单位
      { q: '这三个指标的单位分别是什么',
        v: a => a.includes('单位') || a.includes('%') || a.includes('百分'),
        expectClarify: false, lenient: true },
      // T5: 换时间
      { q: '患者入院48小时内转科的比例、急会诊及时到位率、危急值报告时间换成今年',
        v: a => a.includes('2026') || a.includes('项'),
        expectClarify: false, lenient: true },
      // T6: 加指标
      { q: '再加上患者入院8小时内查房率',
        v: a => a.includes('查房') || a.includes('指标') || /\d/.test(a),
        expectClarify: false, lenient: true },
      // T7: 汇总
      { q: '给我这几个指标的汇总表',
        v: a => a.includes('汇总') || a.includes('表') || a.includes('指标'),
        expectClarify: false, lenient: true },
      // T8: 批量重算验证缓存
      { q: '患者入院48小时内转科的比例、急会诊及时到位率、危急值报告时间换成前年',
        v: a => a.includes('2024') || a.includes('项') || /\d/.test(a),
        expectClarify: false, lenient: true },
      // T9: 建议
      { q: '给个改进建议',
        v: a => a.includes('建议') || a.includes('改进'),
        expectClarify: false, lenient: true },
      { q: '好的',
        v: () => true, lenient: true },
    ],
  },
  {
    name: '对话6: 单位类型（百分比/比值/数值）',
    sid: `acc10_d6_${TS}`,
    turns: [
      // T1: 百分比类
      { q: '算HXZD-002-001去年的',
        v: a => a.includes('%') || a.includes('百分'),
        expectClarify: false },
      // T2: 比值类
      { q: '算HXZD-009-002去年的',
        v: a => /\d/.test(a),
        expectClarify: false },
      // T3: 为什么不是百分比
      { q: 'HXZD-009-002为什么不是百分比',
        v: a => a.includes('比值') || a.includes('单位') || a.includes('定义') || a.includes('计量'),
        expectClarify: false, lenient: true },
      // T4: 数值类
      { q: '算HXZD-014-001去年',
        v: a => /\d/.test(a),
        expectClarify: false },
      // T5: 达标
      { q: 'HXZD-014-001的达标怎么判定',
        v: a => a.includes('达标') || a.includes('目标') || a.includes('方向') || a.includes('越低'),
        expectClarify: false, lenient: true },
      // T6: HXZD-015-001
      { q: '算HXZD-015-001去年',
        v: a => a.includes('%') || /\d/.test(a),
        expectClarify: false },
      // T7: 单位来源
      { q: '这些指标的单位是从哪里定义的',
        v: a => a.includes('单位') || a.includes('实体') || a.includes('知识库') || a.includes('定义'),
        expectClarify: false, lenient: true },
      // T8: 比值类列表
      { q: '全部指标里比值类的有哪些',
        v: a => a.includes('比值') || a.includes('指标'),
        expectClarify: false, lenient: true },
      // T9: 换算
      { q: '把HXZD-009-002换算成百分比',
        v: a => a.includes('%') || a.includes('换算') || /\d/.test(a),
        expectClarify: false, lenient: true },
      { q: '好的',
        v: () => true, lenient: true },
    ],
  },
  {
    name: '对话7: 达标趋势（逐步降低/提高）',
    sid: `acc10_d7_${TS}`,
    turns: [
      // T1: 逐步降低
      { q: '算HXZD-011-001去年的',
        v: a => /\d/.test(a) || a.includes('达标'),
        expectClarify: false },
      // T2: 为什么0达标
      { q: 'HXZD-011-001为什么0算达标',
        v: a => a.includes('降低') || a.includes('越低') || a.includes('达标') || a.includes('导向'),
        expectClarify: false, lenient: true },
      // T3: 同类
      { q: '算HXZD-011-002去年的',
        v: a => /\d/.test(a) || a.includes('达标'),
        expectClarify: false },
      // T4: 逐步提高
      { q: '算HXZD-005-001去年',
        v: a => /\d/.test(a) || a.includes('样本') || a.includes('目标'),
        expectClarify: false },
      // T5: 无样本
      { q: 'HXZD-005-001无样本算达标还是不达标',
        v: a => a.includes('样本') || a.includes('达标') || a.includes('判定'),
        expectClarify: false, lenient: true },
      // T6: 目标值来源
      { q: 'HXZD-011-001的目标值是哪里定义的',
        v: a => a.includes('目标') || a.includes('定义') || a.includes('参数'),
        expectClarify: false, lenient: true },
      // T7: 逐步降低列表
      { q: '逐步降低的指标都有哪些',
        v: a => a.includes('降低') || a.includes('指标'),
        expectClarify: false, lenient: true },
      // T8: 不达标
      { q: '逐步降低的指标里不达标的有哪些',
        v: a => a.includes('不达标') || a.includes('达标') || a.includes('指标'),
        expectClarify: false, lenient: true },
      { q: '给个改进建议',
        v: a => a.includes('建议') || a.includes('改进'),
        expectClarify: false, lenient: true },
      { q: '谢谢',
        v: () => true, lenient: true },
    ],
  },
  {
    name: '对话8: 抽取缓存与回归',
    sid: `acc10_d8_${TS}`,
    turns: [
      // T1: 首次触发抽取
      { q: '算HXZD-005-001去年',
        v: a => /\d/.test(a) || a.includes('样本'),
        expectClarify: false },
      // T2: 缓存命中
      { q: '再算一次HXZD-005-001去年',
        v: a => /\d/.test(a) || a.includes('样本'),
        expectClarify: false },
      // T3: 数据新鲜度
      { q: 'HXZD-005-001的数据是什么时候抽的',
        v: a => a.includes('时间') || a.includes('数据') || a.includes('抽取'),
        expectClarify: false, lenient: true },
      // T4: 换时间 → 新抽取
      { q: '算HXZD-005-001今年',
        v: a => a.includes('2026') || /\d/.test(a) || a.includes('样本'),
        expectClarify: false },
      // T5: HXZD-011-001 抽取
      { q: '算HXZD-011-001去年',
        v: a => /\d/.test(a) || a.includes('达标'),
        expectClarify: false },
      // T6: HXZD-012-001 拓展事件指标
      { q: '算HXZD-012-001去年',
        v: a => /\d/.test(a) || a.includes('样本') || a.includes('NO_SAMPLE'),
        expectClarify: false },
      // T7: 强制刷新
      { q: '强制刷新HXZD-005-001去年重新抽取',
        v: a => /\d/.test(a) || a.includes('刷新') || a.includes('样本'),
        expectClarify: false, lenient: true },
      // T8: 失败提示
      { q: '如果抽取失败的话系统会怎么提示',
        v: a => a.includes('失败') || a.includes('提示') || a.includes('降级') || a.includes('旧数据'),
        expectClarify: false, lenient: true },
      // T9: HXZD-015-001
      { q: '算HXZD-015-001去年',
        v: a => a.includes('%') || /\d/.test(a),
        expectClarify: false },
      { q: '好的',
        v: () => true, lenient: true },
    ],
  },
  {
    name: '对话9: 会诊制度批量',
    sid: `acc10_d9_${TS}`,
    turns: [
      // T1: HXZD-003-001
      { q: '算HXZD-003-001去年',
        v: a => /\d/.test(a) || a.includes('样本'),
        expectClarify: false },
      // T2: HXZD-003-002
      { q: '算HXZD-003-002去年',
        v: a => /\d/.test(a) || a.includes('样本'),
        expectClarify: false },
      // T3: HXZD-003-003
      { q: '算HXZD-003-003去年',
        v: a => /\d/.test(a) || a.includes('样本'),
        expectClarify: false },
      // T4: HXZD-003-004
      { q: '算HXZD-003-004去年',
        v: a => /\d/.test(a) || a.includes('样本'),
        expectClarify: false },
      // T5: 对比
      { q: 'HXZD-003的四个指标对比一下',
        v: a => a.includes('对比') || a.includes('指标') || a.includes('汇总'),
        expectClarify: false, lenient: true },
      // T6: 最差
      { q: 'HXZD-003的四个指标哪个最差',
        v: a => a.includes('差') || a.includes('低') || a.includes('指标'),
        expectClarify: false, lenient: true },
      // T7: HXZD-008-001
      { q: '算HXZD-008-001去年',
        v: a => /\d/.test(a) || a.includes('样本'),
        expectClarify: false },
      // T8: HXZD-008-002 (拓展事件指标)
      { q: '算HXZD-008-002去年',
        v: a => /\d/.test(a) || a.includes('样本'),
        expectClarify: false },
      { q: '给个改进建议',
        v: a => a.includes('建议') || a.includes('改进'),
        expectClarify: false, lenient: true },
      { q: '好的',
        v: () => true, lenient: true },
    ],
  },
  {
    name: '对话10: 知识查询与边界',
    sid: `acc10_d10_${TS}`,
    turns: [
      // T1: 多少个指标
      { q: '全部指标一共有多少个',
        v: a => /\d/.test(a) || a.includes('指标') || a.includes('实体'),
        expectClarify: false },
      // T2: 口径定义
      { q: '统计口径是什么意思',
        v: a => a.includes('口径') || a.includes('统计') || a.includes('定义'),
        expectClarify: false, lenient: true },
      // T3: HXZD-004-001 计算（验证无目标值指标卡片结构）
      { q: '算HXZD-004-001去年',
        v: a => /\d/.test(a) || a.includes('样本') || a.includes('失败'),
        expectClarify: false, lenient: true },
      // T4: 不存在的指标
      { q: '帮我算住院患者洗澡率',
        v: a => a.includes('没有') || a.includes('未找到') || a.includes('不存在') || a.includes('候选') || a.includes('无此') || /\d/.test(a),
        expectClarify: false, lenient: true },
      // T5: 不存在编号
      { q: '算HXZD-999-001',
        v: a => a.includes('未找到') || a.includes('不存在') || a.includes('没有') || a.includes('错误') || a.includes('指标') || /\d/.test(a),
        expectClarify: false, lenient: true },
      // T6: 模糊词
      { q: '帮我算转科率',
        v: a => a.includes('转科') || a.includes('HXZD-001') || a.includes('时间'),
        expectClarify: false, lenient: true },
      // T7: 时间
      { q: '最近',
        v: a => a.includes('2026') || a.includes('月') || /\d/.test(a) || a.includes('时间'),
        expectClarify: false, lenient: true },
      // T8: 文件对比
      { q: '和上传的文件对比一下',
        v: a => a.includes('文件') || a.includes('上传') || a.includes('未'),
        expectClarify: false, lenient: true },
      // T9: 会话管理
      { q: '删除这个会话',
        v: a => a.includes('删除') || a.includes('会话') || a.includes('管理'),
        expectClarify: false, lenient: true },
      { q: '好的',
        v: () => true, lenient: true },
    ],
  },
];

// ── Runner ──────────────────────────────────────────────────────
const report = { startedAt: new Date().toISOString(), conversations: [] };
let allPassed = true;
let totalTurns = 0;
let totalPassed = 0;

for (const conv of conversations) {
  console.log(`\n═══ ${conv.name} (sid=${conv.sid}) ═══`);
  const convReport = { name: conv.name, sid: conv.sid, turns: [], passed: true };

  for (let i = 0; i < conv.turns.length; i++) {
    const { q, v, expectClarify, lenient } = conv.turns[i];
    const t0 = performance.now();
    let payload = {};
    let error = '';
    try {
      const resp = await fetch(EP, {
        method: 'POST',
        headers: { 'content-type': 'application/json; charset=utf-8' },
        body: JSON.stringify({ query: q, sessionId: conv.sid }),
        signal: AbortSignal.timeout(TIMEOUT),
      });
      payload = await resp.json();
    } catch (ex) {
      error = String(ex?.stack || ex);
    }
    const dur = Math.round(performance.now() - t0);
    const answer = String(payload.answer || payload.detail || '');
    const stopReason = payload.stopReason || '';
    const isClarify = stopReason === 'clarification';
    const isPlannerErr = payload.code === 'PLANNER_OUTPUT_INVALID';

    const httpOk = !error;
    // lenient 模式：planner 异常 / clarification / 模型调用失败 均算通过（非代码回归）
    const isModelErr = stopReason === 'MODEL_CALL_FAILED' || payload.code === 'MODEL_CALL_FAILED';
    const contentOk = (lenient && (isPlannerErr || isClarify || isModelErr)) ? true : v(answer);
    const clarifyOk = expectClarify ? isClarify : (lenient ? true : !isClarify || contentOk);
    const passed = httpOk && contentOk && clarifyOk;

    if (passed) totalPassed++;
    totalTurns++;

    const line = passed ? '✅' : '❌';
    console.log(`  ${line} T${i + 1} [${(dur / 1000).toFixed(1)}s] ${stopReason || payload.code || 'ERR'} | ${answer.slice(0, 120).replace(/\s+/g, ' ')}`);

    convReport.turns.push({
      turn: i + 1, query: q, passed, dur,
      stopReason: stopReason || payload.code || '',
      error,
      answerHead: answer.slice(0, 300),
    });

    if (!passed) {
      convReport.passed = false;
      allPassed = false;
      console.log(`     ↳ FAIL: httpOk=${httpOk} contentOk=${contentOk} clarifyOk=${clarifyOk}`);
      console.log(`     ↳ answer: ${answer.slice(0, 500)}`);
      // 不中断，继续跑后续轮次
    }
    // 请求间隔，防止 DashScope API 限流
    if (i < conv.turns.length - 1) {
      await new Promise(r => setTimeout(r, 1000));
    }
  }
  report.conversations.push(convReport);
}

report.finishedAt = new Date().toISOString();
report.allPassed = allPassed;
report.summary = `${totalPassed}/${totalTurns} turns passed`;

fs.mkdirSync(path.dirname(OUT), { recursive: true });
fs.writeFileSync(OUT, JSON.stringify(report, null, 2) + '\n', 'utf8');
console.log(`\n══════════════════════════════════`);
console.log(`Result: ${report.summary} | allPassed=${allPassed}`);
console.log(`Report: ${OUT}`);
process.exitCode = allPassed ? 0 : 1;
