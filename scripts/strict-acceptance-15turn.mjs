import fs from 'node:fs';
import path from 'node:path';

const endpoint = process.env.AGENT_ENDPOINT || 'http://127.0.0.1:8765/api/agent/chat';
const sessionId = process.env.ACCEPTANCE_SESSION
  || `strict_15turn_${new Date().toISOString().replace(/\D/g, '').slice(0, 14)}`;

const turns = [
  ['急会诊及时到位率是什么？', answer => includes(answer, ['急会诊及时到位率', '指标定义'])],
  ['分子和分母呢？', answer => includes(answer, ['分子口径', '分母口径'])],
  ['当前用的是什么口径？', answer => includes(answer, ['口径', '急会诊及时到位率'])],
  ['概览 SQL 怎么写？', answer => includes(answer, ['SQL', '未访问数据库'])],
  ['算一下2026年6月份的结果。', answer => includes(answer, ['2026-06-01', '急会诊及时到位率'])],
  ['时间改成2026年5月份。', answer => includes(answer, ['2026-05-01', '急会诊及时到位率'])],
  ['换成术中自体血回输率。', answer => includes(answer, ['术中自体血回输率', '2026-05-01'])],
  ['我觉得这个指标的分子口径有问题。', answer => includes(answer, [
    '指标异常诊断', 'SKIPPED_DISABLED', '本轮未刷新真实库数据',
  ])],
  ['计算患者入院48小时内转科的比例、急会诊及时到位率、危急值报告时间。',
    answer => includes(answer, [
      '共 3 项', '0 项失败',
      '患者入院48小时内转科', '急会诊及时到位率', '危急值报告时间',
    ])],
  ['这三个指标的定义和口径分别是什么？',
    answer => includes(answer, ['患者入院48小时内转科', '急会诊及时到位率', '危急值报告时间'])],
  ['第三个换成四级手术与三级手术并发症发生率比。',
    answer => includes(answer, ['患者入院48小时内转科', '急会诊及时到位率', '四级手术与三级手术并发症发生率比'])],
  ['最后这个指标的 SQL 怎么写？',
    answer => includes(answer, ['四级手术与三级手术并发症发生率比', 'SQL', '未访问数据库'])],
  ['按上次统计时间计算这三个指标。',
    answer => includes(answer, [
      '共 3 项', '0 项失败', '2026-05-01',
      '四级手术与三级手术并发症发生率比',
    ])],
  ['全部指标。', answer => includes(answer, [
    '共 35 项', '0 项失败', '2026-05-01',
  ])],
  ['把时间改成本月。', answer => includes(answer, [
    '共 35 项', '0 项失败', '2026-07-01',
  ])],
];

const report = {
  startedAt: new Date().toISOString(),
  endpoint,
  sessionId,
  turns: [],
};

for (let index = 0; index < turns.length; index += 1) {
  const [query, validate] = turns[index];
  const started = performance.now();
  let response;
  let payload;
  let error = '';
  try {
    response = await fetch(endpoint, {
      method: 'POST',
      headers: {'content-type': 'application/json; charset=utf-8'},
      body: JSON.stringify({query, sessionId}),
      signal: AbortSignal.timeout(600_000),
    });
    payload = await response.json();
  } catch (exception) {
    error = String(exception?.stack || exception);
    payload = {};
  }
  const answer = String(payload.answer || '');
  const checks = {
    httpOk: Boolean(response?.ok),
    finalAnswer: payload.stopReason === 'final_answer',
    noClarification: payload.clarification == null,
    contentContract: validate(answer),
  };
  const passed = !error && Object.values(checks).every(Boolean);
  report.turns.push({
    turn: index + 1,
    query,
    durationMs: Math.round(performance.now() - started),
    traceId: payload.traceId || '',
    stepCount: payload.stepCount ?? null,
    stopReason: payload.stopReason || '',
    clarification: payload.clarification ?? null,
    checks,
    passed,
    error,
    answer,
  });
  console.log(JSON.stringify({
    turn: index + 1,
    passed,
    durationMs: report.turns.at(-1).durationMs,
    traceId: payload.traceId || '',
    answerHead: answer.slice(0, 160).replace(/\s+/g, ' '),
  }));
  if (!passed) {
    break;
  }
}

report.finishedAt = new Date().toISOString();
report.passed = report.turns.length === turns.length
  && report.turns.every(turn => turn.passed);
const output = path.resolve('output', 'strict-acceptance-15turn.json');
fs.mkdirSync(path.dirname(output), {recursive: true});
fs.writeFileSync(output, `${JSON.stringify(report, null, 2)}\n`, 'utf8');
console.log(JSON.stringify({report: output, passed: report.passed, completedTurns: report.turns.length}));
process.exitCode = report.passed ? 0 : 1;

function includes(answer, values) {
  return values.every(value => answer.includes(value));
}
