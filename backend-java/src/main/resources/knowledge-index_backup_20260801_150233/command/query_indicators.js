#!/usr/bin/env node
// ============================================================
// query_indicators.js — 从 WiNEX_All_QA 查询指标数据 (knowledgeskill v2.0.0)
// ============================================================
// 知识库：knowledge-index-v9
// 用法:
//   node query_indicators.js --indicator Ind01 --start 2025-01-01 --end 2025-02-15
//   node query_indicators.js --system 首诊负责制度 --start 2025-01-01 --end 2025-02-15
//   node query_indicators.js --all --start 2025-01-01 --end 2025-02-15
//   node query_indicators.js --indicator Ind01 --start 2025-01-01 --end 2025-02-15 --dept
//   node query_indicators.js --indicator Ind01 --start 2025-01-01 --end 2025-02-15 --monthly
// ============================================================

const { execSync } = require('child_process');
const path = require('path');
const fs = require('fs');

// ── 数据库连接 ──────────────────────────────────────────────
const DB = {
  server: '172.17.0.117,1455',
  database: 'WiNEX_All_QA',
  user: 'WINDBA',
  password: 'Winning@2023!',
  schema: 'WINDBA_GN'
};

// ── 指标映射表 ──────────────────────────────────────────────
const INDICATOR_MAP = {
  Ind01: { system: '首诊负责制度', event: 'CORE_FDR', table: 'MRAS_BUSINESS_FIRSTVISIT', field: 'TRANSFER_WITHIN_TWO_DAY', direction: '逐步降低', yesLabel: '48h内有转科', noLabel: '48h内无转科' },
  Ind02: { system: '三级查房制度', event: 'CORE_WARDROUND', table: 'MRAS_BUSINESS_WARDROUND', field: 'ROUND_WITHIN_EIGHT_HOUR', direction: '逐步提高', yesLabel: '8h内查房', noLabel: '未在8h内查房' },
  Ind03: { system: '三级查房制度', event: 'CORE_WARDROUND', table: 'MRAS_BUSINESS_WARDROUND', field: 'ORDER_TIME_COMPLIANCE', direction: '逐步提高', yesLabel: '符合', noLabel: '不符合' },
  Ind04: { system: '三级查房制度', event: 'CORE_WARDROUND', table: 'MRAS_BUSINESS_WARDROUND', field: 'UNPLANNED_RETURN', direction: '逐步降低', yesLabel: '非计划重返', noLabel: '无非计划重返' },
  Ind05: { system: '会诊制度', event: 'CORE_CONSUL', table: 'MRAS_BUSINESS_CONSULTATION', field: 'CONSULTATION_TIMELY', direction: '逐步提高', yesLabel: '及时到位', noLabel: '未及时到位' },
  Ind06: { system: '会诊制度', event: 'CORE_CONSUL', table: 'MRAS_BUSINESS_CONSULTATION', field: 'CONSULTATION_EXECUTED', direction: '逐步提高', yesLabel: '已执行', noLabel: '未执行' },
  Ind07: { system: '会诊制度', event: 'CORE_CONSUL', table: 'MRAS_BUSINESS_CONSULTATION', field: 'MULTI_DEPT_CONSULTATION', direction: '逐步提高', yesLabel: '多学科', noLabel: '非多学科' },
  Ind08: { system: '会诊制度', event: 'CORE_CONSUL', table: 'MRAS_BUSINESS_CONSULTATION', field: 'CONSULTATION_RECORD_COMPLETE', direction: '逐步提高', yesLabel: '完整', noLabel: '不完整' },
  Ind09: { system: '分级护理制度', event: 'CORE_GRADED', table: 'MRAS_BUSINESS_GRADED_CARE', field: 'NURSING_GRADE_MATCH', direction: '逐步降低', yesLabel: '不符', noLabel: '相符' },
  Ind10: { system: '值班和交接班制度', event: 'CORE_SHIFTHANDOVER', table: 'MRAS_BUSINESS_SHIFTHANDOVER', field: 'BEDSIDE_HANDOVER', direction: '逐步提高', yesLabel: '床旁交接', noLabel: '非床旁交接' },
  Ind14: { system: '急危重患者抢救制度', event: 'CORE_RESCUE', table: 'MRAS_BUSINESS_PATRESCUE', field: 'RESCUE_RESULTS', direction: '逐步提高', yesLabel: '抢救成功', noLabel: '抢救失败' },
  Ind33: { system: '抗菌药物分级管理制度', event: 'CORE_SPECIAL_ANTI', table: 'MRAS_BUSINESS_ANTI', field: 'APPROVAL_ANTI', direction: '逐步提高', yesLabel: '已审批', noLabel: '未审批' },
  Ind34: { system: '临床用血审核制度', event: 'CORE_BLOOD_RECORD', table: 'MRAS_BUSINESS_BLOOD_AUDIT', field: 'TRANSFUSION_RECORD_STANDARD', direction: '逐步提高', yesLabel: '达标', noLabel: '未达标' },
  Ind35: { system: '临床用血审核制度', event: 'CORE_BLOOD_SURG', table: 'MRAS_BUSINESS_BLOOD_AUDIT', field: 'AUTOLOGOUS_TRANSFUSION', direction: '逐步提高', yesLabel: '有回输', noLabel: '无回输' }
};

// 空表/无表指标
const EMPTY_TABLES = ['Ind11','Ind12','Ind13','Ind15','Ind16','Ind17','Ind18','Ind24','Ind25'];
const NO_TABLE = ['Ind23','Ind30'];
const FIELD_MISMATCH = ['Ind19','Ind20','Ind21','Ind22','Ind26','Ind27','Ind28','Ind29','Ind31','Ind32'];

// ── SQL查询函数 ─────────────────────────────────────────────
function sql(query) {
  const cmd = `sqlcmd -S ${DB.server} -d ${DB.database} -U ${DB.user} -P "${DB.password}" -Q "${query}" -W -s "|" -h-1 2>nul`;
  try {
    const out = execSync(cmd, { encoding: 'utf8', timeout: 30000, windowsHide: true });
    const lines = out.trim().split('\n').filter(l => l.includes('|'));
    return lines.map(l => l.split('|').map(s => s.trim()));
  } catch(e) {
    return [];
  }
}

function dbOk() {
  const rows = sql('SELECT 1 AS ok;');
  return rows.length > 0 && rows[0][0] === '1';
}

// ── 指标查询 ────────────────────────────────────────────────
function queryOverview(ind, start, end) {
  const info = INDICATOR_MAP[ind];
  if (!info) return { error: `Unknown indicator: ${ind}` };

  if (EMPTY_TABLES.includes(ind)) return { error: '空表，无数据', status: 'empty' };
  if (NO_TABLE.includes(ind)) return { error: '无对应MRAS表', status: 'no_table' };

  const q = `
SELECT
    COUNT(*) AS den,
    SUM(CASE WHEN ${info.field} = 98175 THEN 1 ELSE 0 END) AS mol,
    CAST(SUM(CASE WHEN ${info.field} = 98175 THEN 1 ELSE 0 END) * 100.0
         / NULLIF(COUNT(*), 0) AS DECIMAL(10,2)) AS rate
FROM ${DB.schema}.${info.table}
WHERE IS_DEL = 0
  AND EVENT_AT >= '${start}'
  AND EVENT_AT <  DATEADD(DAY, 1, '${end}');
`.trim();

  const rows = sql(q);
  if (rows.length < 2) return { error: '查询无结果', status: 'no_data' };

  const [den, mol, rate] = rows[1];
  return { den: parseInt(den), mol: parseInt(mol), rate: parseFloat(rate), info };
}

function queryEnum(ind, start, end) {
  const info = INDICATOR_MAP[ind];
  if (!info) return [];

  const q = `
SELECT ${info.field}, COUNT(*) AS cnt
FROM ${DB.schema}.${info.table}
WHERE IS_DEL = 0
  AND EVENT_AT >= '${start}'
  AND EVENT_AT < DATEADD(DAY, 1, '${end}')
GROUP BY ${info.field}
ORDER BY ${info.field};
`.trim();

  const rows = sql(q);
  return rows.slice(1).map(r => ({ code: r[0], count: parseInt(r[1]) }));
}

function queryDept(ind, start, end) {
  const info = INDICATOR_MAP[ind];
  if (!info) return [];

  const q = `
SELECT CURRENT_DEPT_NAME,
       COUNT(*) AS den,
       SUM(CASE WHEN ${info.field} = 98175 THEN 1 ELSE 0 END) AS mol,
       CAST(SUM(CASE WHEN ${info.field} = 98175 THEN 1 ELSE 0 END) * 100.0
            / NULLIF(COUNT(*), 0) AS DECIMAL(10,2)) AS rate
FROM ${DB.schema}.${info.table}
WHERE IS_DEL = 0
  AND EVENT_AT >= '${start}'
  AND EVENT_AT < DATEADD(DAY, 1, '${end}')
GROUP BY CURRENT_DEPT_NAME
ORDER BY den DESC;
`.trim();

  const rows = sql(q);
  return rows.slice(1).map(r => ({
    dept: r[0], den: parseInt(r[1]), mol: parseInt(r[2]), rate: parseFloat(r[3])
  }));
}

function queryMonthly(ind, start, end) {
  const info = INDICATOR_MAP[ind];
  if (!info) return [];

  const q = `
SELECT FORMAT(EVENT_AT, 'yyyy-MM'),
       COUNT(*) AS den,
       SUM(CASE WHEN ${info.field} = 98175 THEN 1 ELSE 0 END) AS mol,
       CAST(SUM(CASE WHEN ${info.field} = 98175 THEN 1 ELSE 0 END) * 100.0
            / NULLIF(COUNT(*), 0) AS DECIMAL(5,2)) AS rate
FROM ${DB.schema}.${info.table}
WHERE IS_DEL = 0
  AND EVENT_AT >= '${start}'
  AND EVENT_AT < DATEADD(DAY, 1, '${end}')
GROUP BY FORMAT(EVENT_AT, 'yyyy-MM')
ORDER BY FORMAT(EVENT_AT, 'yyyy-MM');
`.trim();

  const rows = sql(q);
  return rows.slice(1).map(r => ({
    month: r[0], den: parseInt(r[1]), mol: parseInt(r[2]), rate: parseFloat(r[3])
  }));
}

// ── 系统→指标映射 ───────────────────────────────────────────
const SYSTEM_MAP = {
  '首诊负责制度': ['Ind01'],
  '三级查房制度': ['Ind02','Ind03','Ind04'],
  '会诊制度': ['Ind05','Ind06','Ind07','Ind08'],
  '分级护理制度': ['Ind09'],
  '值班和交接班制度': ['Ind10'],
  '疑难病例讨论制度': ['Ind11','Ind12','Ind13'],
  '急危重患者抢救制度': ['Ind14'],
  '术前讨论制度': ['Ind15','Ind16','Ind17','Ind18'],
  '死亡病例讨论制度': ['Ind19','Ind20','Ind21','Ind22'],
  '查对制度': ['Ind23'],
  '手术安全核查制度': ['Ind24','Ind25'],
  '手术分级管理制度': ['Ind26','Ind27','Ind28','Ind29'],
  '新技术和新项目准入制度': ['Ind30'],
  '危急值报告制度': ['Ind31','Ind32'],
  '抗菌药物分级管理制度': ['Ind33'],
  '临床用血审核制度': ['Ind34','Ind35']
};

// ── 主程序 ──────────────────────────────────────────────────
function main() {
  const args = process.argv.slice(2);
  const getArg = (name) => {
    const idx = args.indexOf(`--${name}`);
    return idx >= 0 ? args[idx + 1] : null;
  };
  const hasArg = (name) => args.includes(`--${name}`);

  const start = getArg('start');
  const end = getArg('end');
  const indicator = getArg('indicator');
  const system = getArg('system');
  const all = hasArg('all');
  const dept = hasArg('dept');
  const monthly = hasArg('monthly');
  const json = hasArg('json');

  if (!start || !end) {
    console.error('Usage: --start YYYY-MM-DD --end YYYY-MM-DD [--indicator Ind01] [--system 首诊负责制度] [--all] [--dept] [--monthly] [--json]');
    process.exit(1);
  }

  if (!dbOk()) {
    console.error('Database connection failed!');
    process.exit(2);
  }

  let indicators = [];
  if (all) {
    indicators = Object.keys(INDICATOR_MAP);
  } else if (system) {
    indicators = SYSTEM_MAP[system] || [];
    if (indicators.length === 0) {
      console.error(`Unknown system: ${system}`);
      process.exit(3);
    }
  } else if (indicator) {
    indicators = [indicator];
  } else {
    console.error('Specify --indicator, --system, or --all');
    process.exit(4);
  }

  const results = {};
  for (const ind of indicators) {
    const overview = queryOverview(ind, start, end);
    const enumDist = (overview.status !== 'empty' && overview.status !== 'no_table') ? queryEnum(ind, start, end) : [];
    const deptData = dept ? queryDept(ind, start, end) : [];
    const monthlyData = monthly ? queryMonthly(ind, start, end) : [];

    results[ind] = { overview, enumDist, deptData, monthlyData };
  }

  if (json) {
    console.log(JSON.stringify(results, null, 2));
  } else {
    // 表格输出
    console.log(`\n=== 指标查询结果 (${start} ~ ${end}) ===\n`);
    for (const [ind, data] of Object.entries(results)) {
      const ov = data.overview;
      if (ov.error) {
        console.log(`${ind}: ${ov.error}`);
      } else {
        console.log(`${ind} (${ov.info.system}): ${ov.mol}/${ov.den} = ${ov.rate}%`);
        if (data.enumDist.length > 0) {
          data.enumDist.forEach(e => console.log(`  编码 ${e.code}: ${e.count}`));
        }
        if (data.monthlyData.length > 0) {
          console.log('  月度趋势:');
          data.monthlyData.forEach(m => console.log(`    ${m.month}: ${m.mol}/${m.den} = ${m.rate}%`));
        }
        if (data.deptData.length > 0) {
          console.log('  科室明细 (前5):');
          data.deptData.slice(0, 5).forEach(d => console.log(`    ${d.dept}: ${d.mol}/${d.den} = ${d.rate}%`));
        }
      }
      console.log('');
    }
  }
}

main();
