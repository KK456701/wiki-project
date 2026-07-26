#!/usr/bin/env node
/**
 * build-wiki-from-markdown.mjs
 * 从《35项核心制度指标完整提取.md》一键生成结构化 Wiki 知识库。
 *
 * 用法：
 *   node scripts/build-wiki-from-markdown.mjs --input "core-rules-wiki/raw/company/35项核心制度指标完整提取.md"
 */

import {
  readFileSync,
  writeFileSync,
  mkdirSync,
  rmSync,
  existsSync,
  renameSync,
} from 'node:fs';
import { resolve, dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const PROJECT_ROOT = resolve(__dirname, '..');
const WIKI_ROOT = join(PROJECT_ROOT, 'core-rules-wiki');

// ─── 参数解析 ───────────────────────────────────────────────────────────────────
function parseArgs() {
  const args = process.argv.slice(2);
  let input = null;
  let checkOnly = false;
  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--input' && args[i + 1]) {
      input = args[i + 1];
      i++;
    } else if (args[i] === '--check') {
      checkOnly = true;
    }
  }
  if (!input) {
    console.error('用法: node scripts/build-wiki-from-markdown.mjs --input <markdown文件路径>');
    process.exit(1);
  }
  return {
    inputPath: resolve(PROJECT_ROOT, input),
    checkOnly,
  };
}

// ─── 工具函数 ───────────────────────────────────────────────────────────────────
function today() {
  return new Date().toISOString().slice(0, 10);
}

function ensureDir(dir) {
  mkdirSync(dir, { recursive: true });
}

function writePage(filePath, content) {
  ensureDir(dirname(filePath));
  // 生成文件必须可稳定提交；清理行尾空格，避免源 Markdown 的格式噪声进入产物。
  writeFileSync(filePath, content.replace(/[ \t]+$/gm, ''), 'utf-8');
}

function yamlList(items) {
  if (!items || items.length === 0) return '[]';
  return '\n' + items.map(i => `  - ${i}`).join('\n');
}

/** 构建含 \n 的正则（避免字面量中 \n 被写成换行） */
function re(pattern, flags = '') {
  return new RegExp(pattern, flags);
}

// ─── 解析：指标目录表 ────────────────────────────────────────────────────────────
function parseCatalog(lines) {
  const catalog = [];
  let inTable = false;
  for (const line of lines) {
    if (line.startsWith('| 序号')) { inTable = true; continue; }
    if (inTable && line.startsWith('|---')) continue;
    if (inTable && line.startsWith('|')) {
      const cols = line.split('|').map(c => c.trim()).filter(Boolean);
      if (cols.length >= 5) {
        catalog.push({
          seq: parseInt(cols[0]),
          systemName: cols[1],
          title: cols[2],
          ruleId: cols[3],
          profileCount: parseInt(cols[4]),
        });
      }
    } else if (inTable && !line.startsWith('|')) {
      inTable = false;
    }
  }
  return catalog;
}

// ─── 解析：单个指标 section ──────────────────────────────────────────────────────
function parseIndicatorSection(text, catalogEntry) {
  const indicator = {
    ruleId: catalogEntry.ruleId,
    title: catalogEntry.title,
    systemName: catalogEntry.systemName,
    systemId: catalogEntry.ruleId.replace(/-\d{3}$/, ''),
    definition: '',
    formula: '',
    note: '',
    significance: '',
    profiles: [],
  };

  // 提取基础指标信息（使用 new RegExp 避免 \n 字面量问题）
  const sectionEnd = '(?=\\n####|\\n###|\\n## |\\n---)';

  const defMatch = text.match(re('####\\s*指标定义\\s*\\n([\\s\\S]*?)' + sectionEnd));
  if (defMatch) indicator.definition = defMatch[1].trim();

  const formulaMatch = text.match(re('####\\s*计算公式\\s*\\n([\\s\\S]*?)' + sectionEnd));
  if (formulaMatch) indicator.formula = formulaMatch[1].trim();

  const noteMatch = text.match(re('####\\s*说明\\s*\\n([\\s\\S]*?)' + sectionEnd));
  if (noteMatch) indicator.note = noteMatch[1].trim();

  const sigMatch = text.match(re('####\\s*指标意义\\s*\\n([\\s\\S]*?)' + sectionEnd));
  if (sigMatch) indicator.significance = sigMatch[1].trim();

  // 提取方案 sections
  const profileRegex = re(
    '###\\s*方案\\s*(\\d+)：([^\\n]+)\\n([\\s\\S]*?)(?=\\n###\\s*方案|\\n##\\s*\\d+\\.|\\n---\\s*$|\\s*$)',
    'g',
  );
  let pm;
  while ((pm = profileRegex.exec(text)) !== null) {
    const profileText = pm[0];
    const profileNum = parseInt(pm[1]);
    const profileTitle = pm[2].trim();
    const profile = parseProfile(profileText, profileNum, profileTitle, indicator.ruleId);
    indicator.profiles.push(profile);
  }

  // 如果没有匹配到方案，尝试更宽松的匹配
  if (indicator.profiles.length === 0) {
    const fallback = text.match(/###\s*方案\s*(\d+)：(.+)/);
    if (fallback) {
      const startIdx = text.indexOf(fallback[0]);
      const profileText = text.slice(startIdx);
      const profile = parseProfile(profileText, parseInt(fallback[1]), fallback[2].trim(), indicator.ruleId);
      indicator.profiles.push(profile);
    }
  }

  return indicator;
}

// ─── 解析：单个方案 ──────────────────────────────────────────────────────────────
function parseProfile(text, num, title, ruleId) {
  const profile = {
    num,
    title,
    ruleId,
    meta: {},
    sqlSource: '',
    sqlOverview: '',
    sqlDepartment: '',
    sqlPatientDetail: '',
    numerator: '',
    denominator: '',
    numeratorCaliber: '',
    denominatorCaliber: '',
    configurableParams: '',
  };

  // 解析编码、事件与数据配置
  const configMatch = text.match(re('####\\s*编码、事件与数据配置\\s*\\n([\\s\\S]*?)(?=\\n####)'));
  if (configMatch) {
    const configText = configMatch[1];
    const kvRegex = /-\s*\*\*(.+?)：?\*\*\s*(.*)/g;
    let kv;
    while ((kv = kvRegex.exec(configText)) !== null) {
      const key = kv[1].replace(/：$/, '').trim();
      const value = kv[2].trim();
      profile.meta[key] = value;
    }
  }

  // 解析 SQL blocks
  profile.sqlSource = extractSqlBlock(text, '####\\s*源表\\s*/?\\s*事件抽取\\s*SQL\\s*\\n```sql\\n([\\s\\S]*?)```');
  profile.sqlOverview = extractSqlBlock(text, '####\\s*目标表－概览\\s*SQL\\s*\\n```sql\\n([\\s\\S]*?)```');
  profile.sqlDepartment = extractSqlBlock(text, '####\\s*目标表－科室统计\\s*SQL\\s*\\n```sql\\n([\\s\\S]*?)```');
  profile.sqlPatientDetail = extractSqlBlock(text, '####\\s*目标表－患者明细\\s*SQL\\s*\\n```sql\\n([\\s\\S]*?)```');

  // 解析分子、分母
  const numeratorMatch = text.match(re('#####\\s*分子\\s*\\n([\\s\\S]*?)(?=\\n#####\\s*分母)'));
  if (numeratorMatch) {
    const nText = numeratorMatch[1];
    const contentMatch = nText.match(/\*\*内容：\*\*\s*(.+)/);
    if (contentMatch) profile.numerator = contentMatch[1].trim();
    const caliberMatch = nText.match(re('\\*\\*统计口径：\\*\\*\\s*\\n([\\s\\S]*?)$'));
    if (caliberMatch) profile.numeratorCaliber = caliberMatch[1].trim();
  }

  const denominatorMatch = text.match(re('#####\\s*分母\\s*\\n([\\s\\S]*?)(?=\\n####\\s*可配置参数|\\n###|\\n---|\\s*$)'));
  if (denominatorMatch) {
    const dText = denominatorMatch[1];
    const contentMatch = dText.match(/\*\*内容：\*\*\s*(.+)/);
    if (contentMatch) profile.denominator = contentMatch[1].trim();
    const caliberMatch = dText.match(re('\\*\\*统计口径：\\*\\*\\s*\\n([\\s\\S]*?)$'));
    if (caliberMatch) profile.denominatorCaliber = caliberMatch[1].trim();
  }

  // 解析可配置参数
  const paramMatch = text.match(re('####\\s*可配置参数\\s*\\n([\\s\\S]*?)(?=\\n---|\\n###|\\n## |\\s*$)'));
  if (paramMatch) profile.configurableParams = paramMatch[1].trim();

  return profile;
}

function extractSqlBlock(text, pattern) {
  const m = text.match(re(pattern));
  return m ? m[1].trimEnd() : '';
}

/**
 * 将原始方案状态转换为机器可读状态。
 *
 * 当前批次只把已有SQL作为经过人工整理的知识资产保存。没有字段契约、参数映射和
 * 统一结果列映射前，禁止把SQL错误标记成可执行，避免Agent绕过既有安全预检。
 */
function profileState(profile) {
  const source = [
    profile.title,
    profile.meta['方案类型'],
    profile.meta['方案说明'],
  ].filter(Boolean).join(' ');
  const draft = source.includes('未实现');
  return {
    governanceStatus: draft ? 'draft' : 'approved',
    executionStatus: draft ? 'draft' : 'documentation_only',
    executionBlockers: draft
      ? ['方案标记为未实现']
      : ['缺少经确认的医院字段契约和统一结果列映射'],
  };
}

function sqlReference(ruleId, profileId, type, sql) {
  if (!sql || !sql.trim()) return null;
  return `sql-specs/${ruleId}/profiles/${profileId}/${type}.sql`;
}

function declaredParameters(profile) {
  const values = [
    profile.sqlSource,
    profile.sqlOverview,
    profile.sqlDepartment,
    profile.sqlPatientDetail,
  ].join('\n');
  return [...new Set([...values.matchAll(/:([A-Za-z][A-Za-z0-9_]*)/g)].map(match => match[1]))]
    .sort();
}

function runtimeProfile(indicator, profile) {
  const profileId = getProfileId(indicator.ruleId, profile);
  const state = profileState(profile);
  const isMedianIndicator = /中位数/.test(indicator.formula || '');
  // 中位数等非比例指标天然没有分子、分母。运行契约必须显式说明“不适用”，
  // 不能留空，也不能为了满足字段格式伪造一个比例公式。
  const numeratorRule = profile.numerator || (isMedianIndicator
    ? '不适用（中位数指标；X为每条业务记录的报告耗时）'
    : '不适用（该指标不按分子/分母比例计算）');
  const denominatorRule = profile.denominator || (isMedianIndicator
    ? '不适用（中位数指标；n为纳入统计的有效业务记录数）'
    : '不适用（该指标不按分子/分母比例计算）');
  return {
    profile_id: profileId,
    profile_name: profile.title,
    governance_status: state.governanceStatus,
    execution_status: state.executionStatus,
    execution_blockers: state.executionBlockers,
    effective_from: '2025-01-01',
    effective_to: null,
    owner_scope: 'company',
    hospital_ids: ['*'],
    time_dimension: mapTimeDimension(profile.meta['时间维度']),
    patient_scope: mapPatientScope(profile.meta['患者范围']),
    dedup_key: 'encounter_id',
    direction: mapDirection(profile.meta['指标导向']),
    numerator_rule: numeratorRule,
    numerator_caliber: profile.numeratorCaliber,
    denominator_rule: denominatorRule,
    denominator_caliber: profile.denominatorCaliber,
    configurable_parameters: profile.configurableParams,
    declared_parameters: declaredParameters(profile),
    sql_refs: {
      etl_source: sqlReference(indicator.ruleId, profileId, 'etl_source', profile.sqlSource),
      overview: sqlReference(indicator.ruleId, profileId, 'overview', profile.sqlOverview),
      department: sqlReference(indicator.ruleId, profileId, 'department', profile.sqlDepartment),
      patient_detail: sqlReference(indicator.ruleId, profileId, 'patient_detail', profile.sqlPatientDetail),
    },
    result_mapping: {
      index_value: null,
      numerator_count: null,
      denominator_count: null,
    },
    field_contract: {
      business_fields: {},
    },
    field_mapping: {
      status: 'missing',
      dialect: 'sqlserver',
      db_name: 'winex_aima',
      main_table: profile.meta['中间表'] === '—' ? '' : (profile.meta['中间表'] || ''),
      fields: {},
      parameters: {},
      relations: [],
      query_profile: '',
    },
  };
}

function runtimeManifest(indicator) {
  const profiles = indicator.profiles.map(profile => runtimeProfile(indicator, profile));
  const defaultProfile = profiles.find(profile => profile.execution_status !== 'draft');
  return {
    schema_version: 'hxzd-runtime-v1',
    rule_id: indicator.ruleId,
    rule_name: indicator.title,
    category: indicator.systemName,
    definition: indicator.definition,
    formula: indicator.formula,
    note: indicator.note,
    significance: indicator.significance,
    // 草稿方案只保留为知识来源，不能被运行时当成当前生效口径。
    default_profile: defaultProfile?.profile_id || null,
    profiles,
    quality_checks: [],
  };
}

function writeProfileSql(outputRoot, indicator, profile) {
  const profileId = getProfileId(indicator.ruleId, profile);
  const directory = join(
    outputRoot,
    'sql-specs',
    indicator.ruleId,
    'profiles',
    profileId,
  );
  const items = [
    ['etl_source.sql', profile.sqlSource],
    ['overview.sql', profile.sqlOverview],
    ['department.sql', profile.sqlDepartment],
    ['patient_detail.sql', profile.sqlPatientDetail],
  ];
  for (const [fileName, sql] of items) {
    if (sql && sql.trim()) writePage(join(directory, fileName), sql.trimEnd() + '\n');
  }
}

// ─── 生成：指标主页面 ────────────────────────────────────────────────────────────
function generateIndicatorPage(indicator) {
  const manifest = runtimeManifest(indicator);
  const defaultProfile = manifest.default_profile || '';

  const aliases = [];
  for (const p of indicator.profiles) {
    if (p.meta['指标名称别名']) aliases.push(p.meta['指标名称别名']);
  }

  const keywords = extractKeywords(indicator);
  const direction = mapDirection(indicator.profiles[0]?.meta['指标导向']);
  const unit = mapUnit(indicator.profiles[0]?.meta['计量单位']);

  const fm = [
    '---',
    'page_type: indicator',
    `rule_id: ${indicator.ruleId}`,
    `title: ${indicator.title}`,
    'status: published',
    `system_id: ${indicator.systemId}`,
    `system_name: ${indicator.systemName}`,
    `aliases:${yamlList(aliases)}`,
    `keywords:${yamlList(keywords)}`,
    `direction: ${direction}`,
    `unit: ${unit}`,
    `default_profile: ${defaultProfile || 'null'}`,
    `updated_at: ${today()}`,
    '---',
  ].join('\n');

  const body = [
    '',
    `# ${indicator.title}`,
    '',
    '## 检索卡片',
    '',
    `- 指标编码：${indicator.ruleId}`,
    `- 所属制度：[[${indicator.systemId}-${indicator.systemName}]]`,
    `- 默认口径：[[${defaultProfile}]]`,
    '- SQL规格：[[sql-spec]]',
    '',
    '## 指标定义',
    '',
    indicator.definition || '（待补充）',
    '',
    '## 计算公式',
    '',
    indicator.formula || '（待补充）',
    '',
    '## 指标说明',
    '',
    indicator.note || '（待补充）',
    '',
    '## 指标意义',
    '',
    indicator.significance || '（待补充）',
    '',
    '## 已发布口径',
    '',
    ...indicator.profiles.map(p => `- [[${getProfileId(indicator.ruleId, p)}]] ${p.title}`),
    '',
  ].join('\n');

  return fm + '\n' + body;
}

// ─── 生成：Profile 页面 ──────────────────────────────────────────────────────────
function generateProfilePage(indicator, profile) {
  const profileId = getProfileId(indicator.ruleId, profile);
  const fileName = getProfileFileName(profile);
  const state = profileState(profile);
  const direction = mapDirection(profile.meta['指标导向']);
  const timeDimension = mapTimeDimension(profile.meta['时间维度']);
  const patientScope = mapPatientScope(profile.meta['患者范围']);
  const dedupKey = 'encounter_id';

  const fm = [
    '---',
    'page_type: caliber_profile',
    `profile_id: ${profileId}`,
    `rule_id: ${indicator.ruleId}`,
    `profile_name: ${profile.title}`,
    'owner_scope: company',
    `status: ${state.governanceStatus}`,
    `execution_status: ${state.executionStatus}`,
    'effective_from: 2025-01-01',
    'effective_to:',
    `time_dimension: ${timeDimension}`,
    `patient_scope:${yamlList(patientScope)}`,
    `dedup_key: ${dedupKey}`,
    `direction: ${direction}`,
    `runtime_manifest: ../../../sql-specs/${indicator.ruleId}/runtime.json`,
    `updated_at: ${today()}`,
    '---',
  ].join('\n');

  const metaTable = Object.entries(profile.meta)
    .map(([k, v]) => `| ${k} | ${v} |`)
    .join('\n');

  const body = [
    '',
    `# ${profile.title}`,
    '',
    '## 元数据',
    '',
    '| 字段 | 值 |',
    '|---|---|',
    metaTable,
    '',
    '## 分子',
    '',
    profile.numerator || '（待补充）',
    '',
    '### 统计口径',
    '',
    profile.numeratorCaliber || '（待补充）',
    '',
    '## 分母',
    '',
    profile.denominator || '（待补充）',
    '',
    '### 统计口径',
    '',
    profile.denominatorCaliber || '（待补充）',
    '',
    '## 可配置参数',
    '',
    profile.configurableParams || '无。',
    '',
    '## 执行引用',
    '',
    `- 当前执行状态：\`${state.executionStatus}\``,
    `- 阻断原因：${state.executionBlockers.join('；')}`,
    `- 源表 SQL：\`sql-specs/${indicator.ruleId}/profiles/${profileId}/etl_source.sql\``,
    `- 概览 SQL：\`sql-specs/${indicator.ruleId}/profiles/${profileId}/overview.sql\``,
    `- 科室 SQL：\`sql-specs/${indicator.ruleId}/profiles/${profileId}/department.sql\``,
    `- 患者明细 SQL：\`sql-specs/${indicator.ruleId}/profiles/${profileId}/patient_detail.sql\``,
    '',
  ].join('\n');

  return { fileName, content: fm + '\n' + body };
}

// ─── 生成：SQL Spec 页面 ─────────────────────────────────────────────────────────
function generateSqlSpecPage(indicator, profile) {
  const fm = [
    '---',
    'page_type: sql_spec',
    `rule_id: ${indicator.ruleId}`,
    'database_type: sqlserver',
    'status: published',
    `updated_at: ${today()}`,
    '---',
  ].join('\n');

  const body = [
    '',
    `# SQL 规格：${indicator.title}`,
    '',
    '## etl_source（源表/事件抽取）',
    '',
    '- 用途：T+1 数据抽取和中间表加工',
    '- Agent可执行：否',
    '- 执行方：定时任务（XXJOB）',
    `- 中间表：${profile.meta['中间表'] || '（待确认）'}`,
    `- 源表主表：${profile.meta['源表主表来源'] || '（待确认）'}`,
    '',
    '## overview（概览）',
    '',
    '- 用途：计算全院指标结果',
    '- Agent可执行：是',
    '- 必填参数：begin_at, end_at',
    '- 可选参数：department_ids',
    '- 输出字段：numerator, denominator, rate, target, status',
    '',
    '## department（科室统计）',
    '',
    '- 用途：科室下钻',
    '- Agent可执行：按需',
    '- 必填参数：begin_at, end_at',
    '- 可选参数：dept_id_in, qualified',
    '',
    '## patient_detail（患者明细）',
    '',
    '- 用途：患者明细核对',
    '- Agent可执行：需要显式请求和权限',
    '- 必填参数：begin_at, end_at',
    '- 可选参数：dept_id_in, hospital_area_list, status',
    '',
  ].join('\n');

  return fm + '\n' + body;
}

// ─── 生成：原始 SQL 存档 ─────────────────────────────────────────────────────────
function generateOriginalSqlPage(indicator, profile) {
  const fm = [
    '---',
    'page_type: sql_original',
    `rule_id: ${indicator.ruleId}`,
    'source_status: raw_imported',
    'executable: false',
    'contains_unresolved_tokens: true',
    `updated_at: ${today()}`,
    '---',
  ].join('\n');

  const sections = [];
  if (profile.sqlSource) {
    sections.push(`## 源表 / 事件抽取 SQL\n\n\`\`\`sql\n${profile.sqlSource}\n\`\`\``);
  }
  if (profile.sqlOverview) {
    sections.push(`## 目标表－概览 SQL\n\n\`\`\`sql\n${profile.sqlOverview}\n\`\`\``);
  }
  if (profile.sqlDepartment) {
    sections.push(`## 目标表－科室统计 SQL\n\n\`\`\`sql\n${profile.sqlDepartment}\n\`\`\``);
  }
  if (profile.sqlPatientDetail) {
    sections.push(`## 目标表－患者明细 SQL\n\n\`\`\`sql\n${profile.sqlPatientDetail}\n\`\`\``);
  }

  const body = [
    '',
    `# 原始 SQL 存档：${indicator.title}`,
    '',
    '> ⚠️ 本文件为 Excel 导出的原始 SQL，包含未解析标记（#EQUALS、#ETC、#{NOLOCK}、#NAME?），不可直接执行。',
    '',
    sections.join('\n\n'),
    '',
  ].join('\n');

  return fm + '\n' + body;
}

// ─── 生成：制度页面 ──────────────────────────────────────────────────────────────
function generateSystemPage(systemId, systemName, indicators) {
  const fm = [
    '---',
    'page_type: system',
    `system_id: ${systemId}`,
    `title: ${systemName}`,
    `indicator_count: ${indicators.length}`,
    `updated_at: ${today()}`,
    '---',
  ].join('\n');

  const tableRows = indicators
    .map(i => `| ${i.ruleId} | [[${i.title}]] | ${i.profiles.length} |`)
    .join('\n');

  const body = [
    '',
    `# ${systemName}`,
    '',
    '## 下属指标',
    '',
    '| 编码 | 指标名称 | 方案数 |',
    '|---|---|---:|',
    tableRows,
    '',
  ].join('\n');

  return fm + '\n' + body;
}

// ─── 生成：总索引页 ──────────────────────────────────────────────────────────────
function generateIndexPage(systems) {
  let body = `# 核心制度指标知识库总索引\n\n`;
  body += `> 本页面由脚本自动生成，请勿手动编辑。\n`;
  body += `> 生成时间：${today()}\n\n`;

  for (const [systemId, info] of Object.entries(systems)) {
    body += `## ${info.name}（${systemId}）\n\n`;
    body += `| 编码 | 指标名称 | 方案数 |\n|---|---|---:|\n`;
    for (const ind of info.indicators) {
      body += `| ${ind.ruleId} | [[${ind.title}]] | ${ind.profiles.length} |\n`;
    }
    body += '\n';
  }

  return body;
}

// ─── 辅助映射函数 ────────────────────────────────────────────────────────────────
function getProfileId(ruleId, profile) {
  if (profile.num === 1 || profile.title.includes('推荐')) {
    return `${ruleId}-company-default`;
  }
  return `${ruleId}-company-candidate-${String(profile.num - 1).padStart(2, '0')}`;
}

function getProfileFileName(profile) {
  if (profile.num === 1 || profile.title.includes('推荐')) {
    return 'company-default.md';
  }
  return `company-candidate-${String(profile.num - 1).padStart(2, '0')}.md`;
}

function mapDirection(raw) {
  if (!raw) return 'lower_is_better';
  if (raw.includes('降低') || raw.includes('低')) return 'lower_is_better';
  if (raw.includes('提高') || raw.includes('高')) return 'higher_is_better';
  return 'lower_is_better';
}

function mapUnit(raw) {
  if (!raw) return 'percentage';
  if (raw.includes('百分比') || raw.includes('%')) return 'percentage';
  if (raw.includes('天') || raw.includes('日') || raw.includes('时间')) return 'days';
  if (raw.includes('次') || raw.includes('人')) return 'count';
  if (raw.includes('比')) return 'ratio';
  return 'percentage';
}

function mapTimeDimension(raw) {
  if (!raw) return 'admitted_to_ward_at';
  if (raw.includes('入区')) return 'admitted_to_ward_at';
  if (raw.includes('出院')) return 'discharged_at';
  if (raw.includes('入院')) return 'admitted_at';
  if (raw.includes('手术')) return 'surgery_at';
  if (raw.includes('死亡')) return 'death_at';
  if (raw.includes('医嘱')) return 'order_at';
  if (raw.includes('报告') || raw.includes('危急值')) return 'report_at';
  return 'admitted_to_ward_at';
}

function mapPatientScope(raw) {
  if (!raw) return ['inpatient_current', 'inpatient_discharged'];
  const scopes = [];
  if (raw.includes('在院')) scopes.push('inpatient_current');
  if (raw.includes('出院')) scopes.push('inpatient_discharged');
  if (raw.includes('手术')) scopes.push('surgery_patient');
  if (raw.includes('死亡')) scopes.push('deceased');
  if (scopes.length === 0) scopes.push('inpatient_current', 'inpatient_discharged');
  return scopes;
}

function extractKeywords(indicator) {
  const keywords = new Set();
  const titleWords = indicator.title.match(/[\u4e00-\u9fa5]+/g) || [];
  for (const w of titleWords) {
    if (w.length >= 2 && w.length <= 6) keywords.add(w);
  }
  keywords.add(indicator.systemName);
  for (const p of indicator.profiles) {
    if (p.meta['时间维度']) keywords.add(p.meta['时间维度']);
    if (p.meta['关联事件']) keywords.add(p.meta['事件名称'] || p.meta['关联事件']);
  }
  return [...keywords].slice(0, 8);
}

function validateParsedContent(content, catalog, sections, indicators) {
  const profileCount = indicators.reduce((sum, indicator) => sum + indicator.profiles.length, 0);
  const sqlCount = indicators.reduce((sum, indicator) => sum + indicator.profiles.reduce(
    (profileSum, profile) => profileSum + [
      profile.sqlSource,
      profile.sqlOverview,
      profile.sqlDepartment,
      profile.sqlPatientDetail,
    ].filter(Boolean).length,
    0,
  ), 0);
  const sourceSqlCount = [...content.matchAll(/```sql\s*\r?\n[\s\S]*?```/g)].length;
  const errors = [];

  if (catalog.length !== 35) errors.push(`指标目录应为35项，实际${catalog.length}项`);
  if (sections.length !== 35) errors.push(`指标章节应为35项，实际${sections.length}项`);
  if (indicators.length !== 35) errors.push(`成功解析指标应为35项，实际${indicators.length}项`);
  if (profileCount !== 45) errors.push(`口径方案应为45个，实际${profileCount}个`);
  if (sourceSqlCount !== 169 || sqlCount !== 169) {
    errors.push(`SQL块应为169个，原文${sourceSqlCount}个、解析${sqlCount}个`);
  }

  const ruleIds = new Set();
  const profileIds = new Set();
  for (const indicator of indicators) {
    if (ruleIds.has(indicator.ruleId)) errors.push(`指标编号重复：${indicator.ruleId}`);
    ruleIds.add(indicator.ruleId);
    const expected = catalog.find(item => item.ruleId === indicator.ruleId)?.profileCount;
    if (expected !== indicator.profiles.length) {
      errors.push(`${indicator.ruleId}应有${expected}个方案，实际${indicator.profiles.length}个`);
    }
    for (const profile of indicator.profiles) {
      const profileId = getProfileId(indicator.ruleId, profile);
      if (profileIds.has(profileId)) errors.push(`Profile编号重复：${profileId}`);
      profileIds.add(profileId);
      const state = profileState(profile);
      if (state.executionStatus === 'executable') {
        const unresolved = [
          profile.sqlOverview,
          profile.sqlDepartment,
          profile.sqlPatientDetail,
        ].join('\n').match(/#(?:NAME\?|EQUALS|ETC)/);
        if (unresolved) errors.push(`${profileId}仍包含未解析模板标记`);
        if (!profile.numerator || !profile.denominator) {
          errors.push(`${profileId}缺少分子或分母定义`);
        }
      }
    }
  }

  if (errors.length > 0) {
    throw new Error(`Wiki输入校验失败：\n- ${errors.join('\n- ')}`);
  }
}

function validateStaging(outputRoot, indicators) {
  const requiredIndexes = [
    'indicator_index.json',
    'alias_index.json',
    'keyword_index.json',
    'system_index.json',
    'rule_index.json',
    'profile_index.json',
    'hospital_override_index.json',
    'relation_index.json',
  ];
  const missing = requiredIndexes.filter(fileName =>
    !existsSync(join(outputRoot, 'indexes', fileName)));
  for (const indicator of indicators) {
    if (!existsSync(join(outputRoot, 'wiki', 'indicators', indicator.ruleId, 'index.md'))) {
      missing.push(`wiki/indicators/${indicator.ruleId}/index.md`);
    }
    if (!existsSync(join(outputRoot, 'sql-specs', indicator.ruleId, 'runtime.json'))) {
      missing.push(`sql-specs/${indicator.ruleId}/runtime.json`);
    }
  }
  if (missing.length > 0) {
    throw new Error(`Wiki生成物缺失：\n- ${missing.join('\n- ')}`);
  }
}

/**
 * 生成成功并校验后再替换正式目录。替换期间任一移动失败都会恢复备份，
 * 防止半成品知识库被Java进程读取。
 */
function replaceGeneratedContent(stagingRoot) {
  const backupRoot = join(WIKI_ROOT, `.wiki-build-backup-${process.pid}`);
  const entries = [
    'wiki/indicators',
    'wiki/systems',
    'sql-specs',
    'indexes',
    'index.md',
  ];
  const moved = [];
  ensureDir(backupRoot);
  try {
    for (let index = 0; index < entries.length; index++) {
      const relative = entries[index];
      const source = join(stagingRoot, relative);
      const target = join(WIKI_ROOT, relative);
      const backup = join(backupRoot, String(index));
      ensureDir(dirname(target));
      const hadTarget = existsSync(target);
      if (hadTarget) renameSync(target, backup);
      // 先记录备份状态再移动生成物。这样即使第二次 rename 失败，
      // catch 分支仍能把刚刚移走的正式目录恢复回来。
      moved.push({ target, backup, hadTarget });
      renameSync(source, target);
    }
  } catch (error) {
    for (const item of moved.reverse()) {
      if (existsSync(item.target)) rmSync(item.target, { recursive: true, force: true });
      if (item.hadTarget && existsSync(item.backup)) renameSync(item.backup, item.target);
    }
    throw error;
  } finally {
    if (existsSync(stagingRoot)) rmSync(stagingRoot, { recursive: true, force: true });
    if (existsSync(backupRoot)) rmSync(backupRoot, { recursive: true, force: true });
  }
}

// ─── 主流程 ──────────────────────────────────────────────────────────────────────
function main() {
  const { inputPath, checkOnly } = parseArgs();
  console.log(`📖 读取输入文件: ${inputPath}`);

  if (!existsSync(inputPath)) {
    console.error(`❌ 文件不存在: ${inputPath}`);
    process.exit(1);
  }

  const content = readFileSync(inputPath, 'utf-8');
  const lines = content.split('\n');

  // 1. 解析指标目录
  const catalog = parseCatalog(lines);
  console.log(`📋 解析到 ${catalog.length} 项指标`);

  if (catalog.length === 0) {
    console.error('❌ 未能解析到指标目录表，请检查输入文件格式');
    process.exit(1);
  }

  // 2. 按 ## N. 标题切分各指标 section
  const sectionRegex = /^##\s*(\d+)\.\s*(.+)$/gm;
  const sections = [];
  let sm;
  while ((sm = sectionRegex.exec(content)) !== null) {
    sections.push({ seq: parseInt(sm[1]), title: sm[2].trim(), start: sm.index });
  }

  for (let i = 0; i < sections.length; i++) {
    const end = i + 1 < sections.length ? sections[i + 1].start : content.length;
    sections[i].text = content.slice(sections[i].start, end);
  }

  console.log(`📑 切分到 ${sections.length} 个指标 section`);

  // 3. 解析每个指标
  const indicators = [];
  for (const sec of sections) {
    const catalogEntry = catalog.find(c => c.seq === sec.seq);
    if (!catalogEntry) {
      console.warn(`⚠️ 序号 ${sec.seq} 在目录表中未找到，跳过`);
      continue;
    }
    const indicator = parseIndicatorSection(sec.text, catalogEntry);
    indicators.push(indicator);
  }

  console.log(`✅ 成功解析 ${indicators.length} 项指标`);
  validateParsedContent(content, catalog, sections, indicators);
  if (checkOnly) {
    console.log('✅ 输入文件通过35项指标、45个方案和169个SQL块校验');
    return;
  }

  // 4. 先写入临时目录；只有完整校验通过后才替换正式知识库。
  const outputRoot = join(WIKI_ROOT, `.wiki-build-staging-${process.pid}`);
  if (existsSync(outputRoot)) rmSync(outputRoot, { recursive: true, force: true });
  ensureDir(outputRoot);

  // 5. 生成文件
  const systems = {};
  const indicatorIndex = {};
  const aliasIndex = {};
  const keywordIndex = {};
  const profileIndex = {};
  const ruleIndex = [];
  const relationIndex = {};

  for (const indicator of indicators) {
    const indDir = join(outputRoot, 'wiki', 'indicators', indicator.ruleId);
    const profilesDir = join(indDir, 'profiles');
    const sqlDir = join(outputRoot, 'sql-specs', indicator.ruleId);
    const sqlOriginalDir = join(sqlDir, 'original');

    // 指标主页面
    writePage(join(indDir, 'index.md'), generateIndicatorPage(indicator));

    // Profile 页面
    for (const profile of indicator.profiles) {
      const { fileName, content: profileContent } = generateProfilePage(indicator, profile);
      writePage(join(profilesDir, fileName), profileContent);
      writeProfileSql(outputRoot, indicator, profile);
    }

    // 人读规格保留默认方案摘要；机器运行契约和SQL按每个Profile独立保存。
    const primaryProfile = indicator.profiles[0];
    if (primaryProfile) {
      writePage(join(sqlDir, 'sql-spec.md'), generateSqlSpecPage(indicator, primaryProfile));
      writePage(join(sqlOriginalDir, 'excel-original.md'), generateOriginalSqlPage(indicator, primaryProfile));
    }
    writePage(
      join(sqlDir, 'runtime.json'),
      JSON.stringify(runtimeManifest(indicator), null, 2) + '\n',
    );

    // 制度分组
    if (!systems[indicator.systemId]) {
      systems[indicator.systemId] = { name: indicator.systemName, indicators: [] };
    }
    systems[indicator.systemId].indicators.push(indicator);

    // 索引数据
    const manifest = runtimeManifest(indicator);
    const defaultProfileId = manifest.default_profile;

    indicatorIndex[indicator.ruleId] = {
      title: indicator.title,
      page: `wiki/indicators/${indicator.ruleId}/index.md`,
      system_id: indicator.systemId,
      default_profile: defaultProfileId,
      runtime_manifest: `sql-specs/${indicator.ruleId}/runtime.json`,
    };
    const page = `wiki/indicators/${indicator.ruleId}/index.md`;
    const aliases = indicator.profiles
      .map(profile => profile.meta['指标名称别名'])
      .filter(Boolean);
    ruleIndex.push({
      rule_id: indicator.ruleId,
      rule_name: indicator.title,
      aliases,
      category: indicator.systemName,
      national_path: page,
      company_path: page,
      runtime_path: `sql-specs/${indicator.ruleId}/runtime.json`,
      default_profile: defaultProfileId,
      status: 'active',
      source_path: 'raw/company/35项核心制度指标完整提取.md',
    });
    profileIndex[indicator.ruleId] = manifest.profiles.map(profile => ({
      profile_id: profile.profile_id,
      profile_name: profile.profile_name,
      governance_status: profile.governance_status,
      execution_status: profile.execution_status,
    }));
    relationIndex[indicator.ruleId] = { relations: [] };

    // 别名索引
    aliasIndex[indicator.title] = [indicator.ruleId];
    for (const p of indicator.profiles) {
      if (p.meta['指标名称别名']) {
        const alias = p.meta['指标名称别名'];
        if (!aliasIndex[alias]) aliasIndex[alias] = [];
        if (!aliasIndex[alias].includes(indicator.ruleId)) {
          aliasIndex[alias].push(indicator.ruleId);
        }
      }
    }

    // 关键词索引
    const keywords = extractKeywords(indicator);
    for (const kw of keywords) {
      if (!keywordIndex[kw]) keywordIndex[kw] = [];
      const existing = keywordIndex[kw].find(e => e.rule_id === indicator.ruleId);
      if (!existing) {
        keywordIndex[kw].push({ rule_id: indicator.ruleId, weight: 8 });
      }
    }
  }

  // 6. 生成制度页面
  for (const [systemId, info] of Object.entries(systems)) {
    const fileName = `${systemId}-${info.name}.md`;
    writePage(join(outputRoot, 'wiki', 'systems', fileName), generateSystemPage(systemId, info.name, info.indicators));
  }

  // 7. 生成索引 JSON
  ensureDir(join(outputRoot, 'indexes'));
  writeFileSync(join(outputRoot, 'indexes', 'indicator_index.json'), JSON.stringify(indicatorIndex, null, 2), 'utf-8');
  writeFileSync(join(outputRoot, 'indexes', 'alias_index.json'), JSON.stringify(aliasIndex, null, 2), 'utf-8');
  writeFileSync(join(outputRoot, 'indexes', 'keyword_index.json'), JSON.stringify(keywordIndex, null, 2), 'utf-8');
  writeFileSync(join(outputRoot, 'indexes', 'profile_index.json'), JSON.stringify(profileIndex, null, 2), 'utf-8');
  writeFileSync(join(outputRoot, 'indexes', 'rule_index.json'), JSON.stringify({
    schema_version: 'hxzd-runtime-v1',
    generated_at: today(),
    rules: ruleIndex,
  }, null, 2), 'utf-8');
  writeFileSync(join(outputRoot, 'indexes', 'hospital_override_index.json'), JSON.stringify({
    schema_version: 'hxzd-runtime-v1',
    generated_at: today(),
    hospital_overrides: [],
  }, null, 2), 'utf-8');
  writeFileSync(join(outputRoot, 'indexes', 'relation_index.json'), JSON.stringify(relationIndex, null, 2), 'utf-8');

  const systemIndex = {};
  for (const [systemId, info] of Object.entries(systems)) {
    systemIndex[systemId] = {
      name: info.name,
      indicators: info.indicators.map(i => i.ruleId),
    };
  }
  writeFileSync(join(outputRoot, 'indexes', 'system_index.json'), JSON.stringify(systemIndex, null, 2), 'utf-8');

  // 8. 生成总索引页
  writeFileSync(join(outputRoot, 'index.md'), generateIndexPage(systems), 'utf-8');
  validateStaging(outputRoot, indicators);
  replaceGeneratedContent(outputRoot);

  // 9. 统计输出
  const totalProfiles = indicators.reduce((sum, i) => sum + i.profiles.length, 0);
  console.log('');
  console.log('═══════════════════════════════════════════');
  console.log('  Wiki 知识库生成完成');
  console.log('═══════════════════════════════════════════');
  console.log(`  指标页面：${indicators.length}`);
  console.log(`  口径 Profile：${totalProfiles}`);
  console.log(`  制度页面：${Object.keys(systems).length}`);
  console.log(`  SQL 规格：${indicators.filter(i => i.profiles.length > 0).length}`);
  console.log(`  索引文件：8`);
  console.log('═══════════════════════════════════════════');
}

main();
