#!/usr/bin/env node
/**
 * build-wiki-from-markdown.mjs
 * 从《35项核心制度指标完整提取.md》一键生成结构化 Wiki 知识库。
 *
 * 用法：
 *   node scripts/build-wiki-from-markdown.mjs --input "core-rules-wiki/raw/company/35项核心制度指标完整提取.md"
 */

import { readFileSync, writeFileSync, mkdirSync, rmSync, existsSync } from 'node:fs';
import { resolve, dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const PROJECT_ROOT = resolve(__dirname, '..');
const WIKI_ROOT = join(PROJECT_ROOT, 'core-rules-wiki');

// ─── 参数解析 ───────────────────────────────────────────────────────────────────
function parseArgs() {
  const args = process.argv.slice(2);
  let input = null;
  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--input' && args[i + 1]) {
      input = args[i + 1];
      i++;
    }
  }
  if (!input) {
    console.error('用法: node scripts/build-wiki-from-markdown.mjs --input <markdown文件路径>');
    process.exit(1);
  }
  return resolve(PROJECT_ROOT, input);
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
  writeFileSync(filePath, content, 'utf-8');
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
  const profileRegex = re('###\\s*方案\\s*(\\d+)：(.+?)(?=\\n###\\s*方案|\\n##\\s*\\d+\\.|\\n---\\s*$|\\s*$)', 'g');
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

// ─── 生成：指标主页面 ────────────────────────────────────────────────────────────
function generateIndicatorPage(indicator) {
  const defaultProfile = indicator.profiles.length > 0
    ? getProfileId(indicator.ruleId, indicator.profiles[0])
    : `${indicator.ruleId}-company-default`;

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
    `default_profile: ${defaultProfile}`,
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
    'status: published',
    'effective_from: 2025-01-01',
    'effective_to:',
    `time_dimension: ${timeDimension}`,
    `patient_scope:${yamlList(patientScope)}`,
    `dedup_key: ${dedupKey}`,
    `direction: ${direction}`,
    `sql_spec: ../../../sql-specs/${indicator.ruleId}/sql-spec.md`,
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
    '- 源表 SQL：`etl_source`',
    '- 概览 SQL：`overview`',
    '- 科室 SQL：`department`',
    '- 患者明细 SQL：`patient_detail`',
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

// ─── 主流程 ──────────────────────────────────────────────────────────────────────
function main() {
  const inputPath = parseArgs();
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

  // 4. 清理旧的生成内容（保留 raw/ 和静态文件）
  const generatedDirs = [
    join(WIKI_ROOT, 'wiki', 'indicators'),
    join(WIKI_ROOT, 'wiki', 'systems'),
    join(WIKI_ROOT, 'sql-specs'),
    join(WIKI_ROOT, 'indexes'),
  ];
  for (const dir of generatedDirs) {
    if (existsSync(dir)) rmSync(dir, { recursive: true, force: true });
    ensureDir(dir);
  }

  // 5. 生成文件
  const systems = {};
  const indicatorIndex = {};
  const aliasIndex = {};
  const keywordIndex = {};

  for (const indicator of indicators) {
    const indDir = join(WIKI_ROOT, 'wiki', 'indicators', indicator.ruleId);
    const profilesDir = join(indDir, 'profiles');
    const sqlDir = join(WIKI_ROOT, 'sql-specs', indicator.ruleId);
    const sqlOriginalDir = join(sqlDir, 'original');

    // 指标主页面
    writePage(join(indDir, 'index.md'), generateIndicatorPage(indicator));

    // Profile 页面
    for (const profile of indicator.profiles) {
      const { fileName, content: profileContent } = generateProfilePage(indicator, profile);
      writePage(join(profilesDir, fileName), profileContent);
    }

    // SQL Spec + 原始 SQL（使用第一个方案）
    const primaryProfile = indicator.profiles[0];
    if (primaryProfile) {
      writePage(join(sqlDir, 'sql-spec.md'), generateSqlSpecPage(indicator, primaryProfile));
      writePage(join(sqlOriginalDir, 'excel-original.md'), generateOriginalSqlPage(indicator, primaryProfile));
    }

    // 制度分组
    if (!systems[indicator.systemId]) {
      systems[indicator.systemId] = { name: indicator.systemName, indicators: [] };
    }
    systems[indicator.systemId].indicators.push(indicator);

    // 索引数据
    const defaultProfileId = indicator.profiles.length > 0
      ? getProfileId(indicator.ruleId, indicator.profiles[0])
      : `${indicator.ruleId}-company-default`;

    indicatorIndex[indicator.ruleId] = {
      title: indicator.title,
      page: `wiki/indicators/${indicator.ruleId}/index.md`,
      system_id: indicator.systemId,
      default_profile: defaultProfileId,
    };

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
    writePage(join(WIKI_ROOT, 'wiki', 'systems', fileName), generateSystemPage(systemId, info.name, info.indicators));
  }

  // 7. 生成索引 JSON
  writeFileSync(join(WIKI_ROOT, 'indexes', 'indicator_index.json'), JSON.stringify(indicatorIndex, null, 2), 'utf-8');
  writeFileSync(join(WIKI_ROOT, 'indexes', 'alias_index.json'), JSON.stringify(aliasIndex, null, 2), 'utf-8');
  writeFileSync(join(WIKI_ROOT, 'indexes', 'keyword_index.json'), JSON.stringify(keywordIndex, null, 2), 'utf-8');

  const systemIndex = {};
  for (const [systemId, info] of Object.entries(systems)) {
    systemIndex[systemId] = {
      name: info.name,
      indicators: info.indicators.map(i => i.ruleId),
    };
  }
  writeFileSync(join(WIKI_ROOT, 'indexes', 'system_index.json'), JSON.stringify(systemIndex, null, 2), 'utf-8');

  // 8. 生成总索引页
  writeFileSync(join(WIKI_ROOT, 'index.md'), generateIndexPage(systems), 'utf-8');

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
  console.log(`  索引文件：4`);
  console.log('═══════════════════════════════════════════');
}

main();
