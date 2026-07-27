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
  readdirSync,
  statSync,
} from 'node:fs';
import { resolve, dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';

const __dirname = dirname(fileURLToPath(import.meta.url));
const PROJECT_ROOT = resolve(__dirname, '..');
const WIKI_ROOT = join(PROJECT_ROOT, 'core-rules-wiki');
const REAL_SCHEMA_CONTRACT_PATH = join(
  WIKI_ROOT, 'contracts', 'winex_aima-dbo-schema.json',
);
const REAL_SCHEMA_CONTRACT = existsSync(REAL_SCHEMA_CONTRACT_PATH)
  ? JSON.parse(readFileSync(REAL_SCHEMA_CONTRACT_PATH, 'utf8'))
  : null;

// ─── 参数解析 ───────────────────────────────────────────────────────────────────
function parseArgs() {
  const args = process.argv.slice(2);
  let input = null;
  let checkOnly = false;
  let outputRoot = null;
  let releaseId = null;
  let sourcePath = null;
  let modelId = null;
  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--input' && args[i + 1]) {
      input = args[i + 1];
      i++;
    } else if (args[i] === '--check') {
      checkOnly = true;
    } else if (args[i] === '--output-root' && args[i + 1]) {
      outputRoot = args[++i];
    } else if (args[i] === '--release-id' && args[i + 1]) {
      releaseId = args[++i];
    } else if (args[i] === '--source-path' && args[i + 1]) {
      sourcePath = args[++i];
    } else if (args[i] === '--model-id' && args[i + 1]) {
      modelId = args[++i];
    }
  }
  if (!input) {
    console.error('用法: node scripts/build-wiki-from-markdown.mjs --input <markdown文件路径>');
    process.exit(1);
  }
  return {
    inputPath: resolve(PROJECT_ROOT, input),
    checkOnly,
    outputRoot: outputRoot ? resolve(PROJECT_ROOT, outputRoot) : null,
    releaseId,
    sourcePath,
    modelId,
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

function parseKnowledgeDraft(content) {
  const draft = JSON.parse(content);
  if (draft.schema_version !== 'knowledge-draft-v2' || !Array.isArray(draft.indicators)) {
    throw new Error('KnowledgeDraft必须使用knowledge-draft-v2且包含indicators数组');
  }
  if (!draft.source || !String(draft.source.file_name || '').trim()
      || !/^[a-f0-9]{64}$/.test(String(draft.source.sha256 || ''))) {
    throw new Error('KnowledgeDraft缺少有效的来源文件名或SHA-256');
  }
  if (!Array.isArray(draft.unresolved_questions)) {
    throw new Error('KnowledgeDraft必须显式提供unresolved_questions数组');
  }
  const sqlBlocks = draft.sql_blocks && typeof draft.sql_blocks === 'object'
    ? draft.sql_blocks : {};
  const catalog = [];
  const seenRuleIds = new Set();
  const referencedBlockIds = new Set();
  const indicators = draft.indicators.map((item, index) => {
    const profiles = Array.isArray(item.profiles) ? item.profiles : [];
    const ruleId = String(item.rule_id || '').trim();
    if (!/^HXZD-\d{3}-\d{3}$/.test(ruleId)) {
      throw new Error(`第${index + 1}项指标编号无效：${ruleId}`);
    }
    if (seenRuleIds.has(ruleId)) throw new Error(`KnowledgeDraft包含重复指标编号：${ruleId}`);
    seenRuleIds.add(ruleId);
    if (!String(item.rule_name || '').trim() || !String(item.system_name || '').trim()
        || !String(item.definition || '').trim() || !String(item.formula || '').trim()) {
      throw new Error(`${ruleId}缺少名称、制度、定义或公式`);
    }
    if (!Array.isArray(item.source_refs) || item.source_refs.length === 0
        || !item.source_refs.every(value => String(value || '').trim())) {
      throw new Error(`${ruleId}缺少可追溯的source_refs`);
    }
    if (typeof item.confidence !== 'number' || item.confidence < 0 || item.confidence > 1) {
      throw new Error(`${ruleId}的confidence必须在0到1之间`);
    }
    if (profiles.length === 0) throw new Error(`${ruleId}至少需要一个Profile`);
    catalog.push({
      seq: index + 1,
      systemName: String(item.system_name || '').trim(),
      title: String(item.rule_name || '').trim(),
      ruleId,
      profileCount: profiles.length,
    });
    return {
      ruleId,
      title: String(item.rule_name || '').trim(),
      systemName: String(item.system_name || '').trim(),
      systemId: String(item.system_id || ruleId.replace(/-\d{3}$/, '')).trim(),
      definition: String(item.definition || '').trim(),
      formula: String(item.formula || '').trim(),
      note: String(item.note || '').trim(),
      significance: String(item.significance || '').trim(),
      profiles: profiles.map((profile, profileIndex) => {
        if (!Array.isArray(profile.source_refs) || profile.source_refs.length === 0
            || !profile.source_refs.every(value => String(value || '').trim())) {
          throw new Error(`${ruleId}的第${profileIndex + 1}个Profile缺少source_refs`);
        }
        if (typeof profile.confidence !== 'number'
            || profile.confidence < 0 || profile.confidence > 1) {
          throw new Error(`${ruleId}的第${profileIndex + 1}个Profile confidence无效`);
        }
        const refs = profile.sql_refs && typeof profile.sql_refs === 'object'
          ? profile.sql_refs : {};
        const resolveBlock = name => {
          const blockId = refs[name];
          if (!blockId) return '';
          if (!(blockId in sqlBlocks)) {
            throw new Error(`${ruleId}的${name}引用不存在SQL块：${blockId}`);
          }
          referencedBlockIds.add(blockId);
          return String(sqlBlocks[blockId]);
        };
        return {
          num: profileIndex + 1,
          title: String(profile.profile_name || `方案${profileIndex + 1}`).trim(),
          ruleId,
          meta: profile.meta && typeof profile.meta === 'object' ? profile.meta : {},
          sqlSource: resolveBlock('source_extract'),
          sqlOverview: resolveBlock('overview'),
          sqlDepartment: resolveBlock('department_detail'),
          sqlPatientDetail: resolveBlock('patient_detail'),
          numerator: String(profile.numerator || '').trim(),
          denominator: String(profile.denominator || '').trim(),
          numeratorCaliber: String(profile.numerator_caliber || '').trim(),
          denominatorCaliber: String(profile.denominator_caliber || '').trim(),
          configurableParams: String(profile.configurable_parameters || '').trim(),
        };
      }),
    };
  });
  const referencedSqlCount = indicators.reduce((sum, indicator) => sum + indicator.profiles.reduce(
    (value, profile) => value + [
      profile.sqlSource, profile.sqlOverview, profile.sqlDepartment, profile.sqlPatientDetail,
    ].filter(Boolean).length, 0), 0);
  const unreferencedBlocks = Object.keys(sqlBlocks).filter(id => !referencedBlockIds.has(id));
  if (unreferencedBlocks.length > 0) {
    throw new Error(`KnowledgeDraft丢失了${unreferencedBlocks.length}个SQL块引用：${unreferencedBlocks.slice(0, 5).join('、')}`);
  }
  return {
    catalog,
    sections: catalog.map(item => ({ seq: item.seq, title: item.title, text: '' })),
    indicators,
    sourceSqlCount: referencedSqlCount,
  };
}

function sha256(value) {
  return createHash('sha256').update(value).digest('hex');
}

function compactText(value, maxLength = 360) {
  const compact = String(value || '')
    .replace(/[`*_>#|]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
  return compact.length <= maxLength ? compact : `${compact.slice(0, maxLength - 1)}…`;
}

function validSearchTerm(value) {
  const normalized = String(value || '').trim();
  if (!normalized || /^(?:—|-|无|暂无|不适用|n\/?a)$/i.test(normalized)) return '';
  return normalized;
}

function uniqueSearchTerms(values) {
  return [...new Set(values.map(validSearchTerm).filter(Boolean))];
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

const PARAMETER_ALIASES = new Map([
  ['startTime', 'start_time'],
  ['marptBeginAt', 'start_time'],
  ['endTime', 'end_time'],
  ['marptEndAt', 'end_time'],
  // SQL原文继续使用 :hospital_soid；机器契约使用更明确的业务名称，
  // 防止把991827误解为数据库编号。
  ['hospital_soid', 'hospital_scope_value'],
]);

const SQL_SERVER_FUNCTIONS = new Set([
  'abs', 'avg', 'cast', 'ceiling', 'coalesce', 'concat', 'convert', 'count',
  'current_timestamp', 'charindex', 'dateadd', 'datediff', 'datename', 'datepart', 'day',
  'dense_rank', 'floor', 'format', 'getdate', 'iif', 'isnull', 'lag', 'lead',
  'left', 'len', 'lower', 'ltrim', 'max', 'min', 'month', 'nullif', 'rank',
  'replace', 'right', 'round', 'row_number', 'rtrim', 'stuff', 'substring',
  'sum', 'try_cast', 'try_convert', 'upper', 'year',
]);

/**
 * 只处理可以机械证明不改变业务语义的错误，并返回逐条修复记录。
 *
 * 参数名、表字段、条件、JOIN、阈值、聚合和输出别名均原样保留。参数兼容由 Java
 * 绑定适配器完成，不能借“规范化”之名改写正式 SQL。
 */
function normalizeSqlWithCorrections(sql) {
  if (!sql || !sql.trim()) return { sql: '', corrections: [] };
  let value = sql;
  const corrections = [];

  function lineAt(text, index) {
    return text.slice(0, Math.max(0, index)).split(/\r?\n/).length;
  }

  function replace(pattern, replacement, type, reason) {
    value = value.replace(pattern, (...args) => {
      const matched = args[0];
      const offset = args.at(-2);
      const next = typeof replacement === 'function'
        ? replacement(...args)
        : replacement;
      if (matched !== next) {
        corrections.push({
          type,
          line: lineAt(value, offset),
          before: matched,
          after: next,
          reason,
        });
      }
      return next;
    });
  }

  replace(
    /\bWITH\s*#\{NOLOCK\}/gi,
    'WITH (NOLOCK)',
    'invalid_nolock_placeholder',
    '将无法执行的NOLOCK模板标记修正为SQL Server标准表提示',
  );
  replace(
    /#\{NOLOCK\}/gi,
    'WITH (NOLOCK)',
    'invalid_nolock_placeholder',
    '将无法执行的NOLOCK模板标记修正为SQL Server标准表提示',
  );
  replace(
    /(?<!WITH )\(\s*NOLOCK\s*\)/gi,
    'WITH (NOLOCK)',
    'invalid_nolock_syntax',
    '补齐SQL Server表提示所需的WITH关键字',
  );
  replace(
    /(\bWHERE[ \t]*(?:\r?\n)?(?:[ \t]*--[^\r\n]*(?:\r?\n|$)[ \t]*)+)AND\b/gi,
    (...args) => args[1],
    'dangling_where_and',
    'WHERE后只有注释时删除首个悬空AND，保留原条件和注释',
  );
  replace(
    /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/g,
    '',
    'invalid_control_character',
    '移除SQL文本中不可执行且不承载业务语义的控制字符',
  );
  return { sql: value.trimEnd(), corrections };
}

function normalizeSql(sql) {
  return normalizeSqlWithCorrections(sql).sql;
}

function stripSqlCommentsAndStrings(sql) {
  return sql
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/--[^\r\n]*/g, ' ')
    .replace(/N?'(?:''|[^'])*'/gi, "''")
    .replace(/"(?:""|[^"])*"/g, '""')
    .replace(/\[(?:\]\]|[^\]])*\]/g, '[]');
}

function sqlParameters(sql) {
  return [...new Set(
    [...normalizeSql(sql).matchAll(/:([A-Za-z_][A-Za-z0-9_]*)/g)]
      .map(match => match[1]),
  )].sort();
}

function resultMappingCandidates(sql) {
  const aliases = [...normalizeSql(sql).matchAll(
    /\bAS\s+(?:\[([^\]]+)\]|"([^"]+)"|'([^']+)'|([^\s,()]+))/gi,
  )].map(match => match[1] || match[2] || match[3] || match[4]).filter(Boolean);
  const pick = pattern => aliases.find(alias => pattern.test(alias)) || null;
  const pickExact = names => aliases.find(alias => (
    names.some(name => alias.toLowerCase() === name.toLowerCase())
  )) || null;
  // SQL 常在 CTE 中使用 target_value，最终 SELECT 再输出为“目标值”。
  // 运行契约必须绑定数据库最终结果列，不能被更早出现的内部列名抢先匹配。
  const targetAlias = aliases.find(alias => /目标值/i.test(alias))
    || aliases.find(alias => /^target_value$/i.test(alias))
    || null;
  return {
    numerator_count: pick(/(?:^|_)numerator(?:_count)?$|分子/i),
    denominator_count: pick(/(?:^|_)denominator(?:_count)?$|sample_count|分母/i),
    // 正式机器列优先于前面 CTE 中的“监测情况”等展示列，避免标量契约
    // 被较早出现的宽泛候选污染。
    index_value: pickExact(['index_value', 'result_value'])
      || pick(/指标值|监测情况|比率|比例|率$/i),
    component_left: pick(/分子/i),
    component_right: pick(/分母/i),
    sample_count: pickExact(['sample_count']) || pick(/样本数/i),
    target_value: targetAlias,
  };
}

function sqlFunctions(sql) {
  const clean = stripSqlCommentsAndStrings(normalizeSql(sql));
  const ignored = new Set([
    // 这些词后面可能紧跟分组括号或 SQL Server 语法括号，但并不是函数。
    'and', 'as', 'case', 'cross', 'decimal', 'exists', 'from', 'full', 'in',
    'inner', 'join', 'not', 'numeric', 'on', 'or', 'outer', 'over',
    'partition', 'path', 'select', 'then', 'values', 'varchar', 'when',
    'where', 'with',
  ]);
  return [...new Set(
    [...clean.matchAll(/\b([A-Za-z_][A-Za-z0-9_]*)\s*\(/g)]
      .map(match => match[1].toLowerCase())
      .filter(name => !ignored.has(name)),
  )].sort();
}

function splitSqlStatements(sql) {
  const clean = stripSqlCommentsAndStrings(sql);
  return clean.split(';').map(item => item.trim()).filter(Boolean);
}

function validateSqlCapability(type, rawSql) {
  if (!rawSql || !rawSql.trim()) {
    return {
      capability: type,
      status: 'missing',
      blockers: ['来源文件未提供该类 SQL'],
      parameters: [],
      functions: [],
      unknown_functions: [],
    };
  }
  const normalized = normalizeSql(rawSql);
  const clean = stripSqlCommentsAndStrings(normalized);
  const upper = clean.trim().toUpperCase();
  const blockers = [];
  const errors = normalized.match(/#(?:NAME\?|EQUALS|ETC)|\{\{|\{%|#\{(?!NOLOCK\})/gi) || [];
  if (errors.length > 0) blockers.push(`包含未解析模板或 Excel 错误：${[...new Set(errors)].join('、')}`);
  if (splitSqlStatements(normalized).length > 1) blockers.push('包含多条 SQL 语句');
  if (!/^(?:SELECT|WITH)\b/.test(upper)) blockers.push('不是 SELECT 或 WITH...SELECT 只读查询');
  if (/\b(?:INSERT|UPDATE|DELETE|DROP|ALTER|TRUNCATE|CREATE|MERGE|EXEC(?:UTE)?|GRANT|REVOKE)\b/i.test(clean)) {
    blockers.push('包含写入、DDL 或过程调用关键字');
  }
  if (/\b(?:sp_executesql|openrowset|opendatasource)\b/i.test(clean)) {
    blockers.push('包含动态 SQL 或外部数据源调用');
  }
  if (/(?:^|[^\w])#[A-Za-z_][A-Za-z0-9_]*/.test(clean)) blockers.push('包含临时表');
  const functions = sqlFunctions(normalized);
  const unknownFunctions = functions.filter(name => !SQL_SERVER_FUNCTIONS.has(name));
  if (unknownFunctions.length > 0) {
    blockers.push(`函数需要目标数据库验证：${unknownFunctions.join('、')}`);
  }
  return {
    capability: type,
    status: blockers.length === 0 ? 'static_validated' : 'verification_required',
    blockers,
    parameters: sqlParameters(normalized),
    functions,
    unknown_functions: unknownFunctions,
    normalized_sha256: sha256(normalized),
  };
}

function declaredParameters(profile) {
  const values = [
    profile.sqlSource,
    profile.sqlOverview,
    profile.sqlDepartment,
    profile.sqlPatientDetail,
  ].join('\n');
  return sqlParameters(values);
}

const EXTRACTION_EVENT_TABLES = new Map([
  ['CORE_FDR', 'MRAS_BUSINESS_FIRSTVISIT'],
  ['CORE_WARDROUND', 'MRAS_BUSINESS_WARDROUND'],
  ['CORE_CONSUL', 'MRAS_BUSINESS_CONSULTATION'],
  ['CORE_CONSUL_OUT', 'MRAS_BUSINESS_CONSULTATION'],
  ['GRADE_CARE_V2', 'MRAS_BUSINESS_GRADED_CARE'],
  ['CORE_SHIFTHANDOVER', 'MRAS_BUSINESS_SHIFTHANDOVER'],
  ['CORE_DIFFI_EMR', 'MRAS_BUSINESS_DIFFI_EMR'],
  ['CORE_DIFFI_EMR_SECOND', 'MRAS_BUSINESS_DIFFI_EMR_SECOND'],
  ['CORE_RESCUE', 'MRAS_BUSINESS_PATRESCUE'],
  ['CORE_OP_DISC', 'MRAS_BUSINESS_OP_DISC'],
  ['CORE_OP_DISC_V2', 'MRAS_BUSINESS_OP_DISC'],
  ['CORE_DEATH', 'MRAS_BUSINESS_DEATH'],
  ['CORE_DEATH_EXT', 'MRAS_BUSINESS_DEATH'],
  ['CORE_SURGERY', 'MRAS_BUSINESS_SURGERY'],
  ['CORE_SUR_GRADE', 'MRAS_BUSINESS_SUR_GRADE'],
  ['CORE_SUR_GRADE_V2', 'MRAS_BUSINESS_SUR_GRADE'],
  ['CORE_CV_RPT', 'MRAS_BUSINESS_CRITICAL_RPT'],
  ['CORE_SPECIAL_ANTI', 'MRAS_BUSINESS_ANTI'],
  ['CORE_SPECIAL_ANTI_EXT', 'MRAS_BUSINESS_ANTI'],
  ['CORE_BLOOD_RECORD', 'MRAS_BUSINESS_BLOOD_AUDIT'],
  ['CORE_BLOOD_SURG', 'MRAS_PATIENT_EVENT'],
]);

const EXTRACTION_RULE_DEPENDENCIES = new Map([
  ['HXZD-010-001', ['INP_CLI_ORDER', 'INPATIENT_ENCOUNTER', 'ORGANIZATION']],
  ['HXZD-012-001', ['CLIBASIC_SURGERY', 'MRAS_PATIENT_EVENT']],
  ['HXZD-012-002', ['CLIBASIC_SURGERY', 'MRAS_PATIENT_EVENT']],
  ['HXZD-012-003', ['CLIBASIC_SURGERY', 'MRAS_PATIENT_EVENT']],
  ['HXZD-012-004', ['CLIBASIC_SURGERY', 'MRAS_PATIENT_EVENT']],
  ['HXZD-013-001', ['MRAS_MEDTECH_PRO', 'MRAS_MEDTECH_PROC']],
  ['HXZD-016-002', ['MRAS_INDEX_SURGREC', 'MRAS_PATIENT_EVENT']],
]);

function extractionContract(indicator, profile) {
  const rawEvent = String(profile.meta['关联事件'] || '').trim();
  const eventNo = rawEvent && rawEvent !== '无' && rawEvent !== '—' ? rawEvent : '';
  const eventTable = eventNo ? EXTRACTION_EVENT_TABLES.get(eventNo) || '' : '';
  const dependencies = [
    'MRAS_TARGET_DEFINITION',
    ...(EXTRACTION_RULE_DEPENDENCIES.get(indicator.ruleId) || []),
  ].filter((value, index, values) => value && value !== eventTable && values.indexOf(value) === index);
  const targetTables = [eventTable, ...dependencies].filter(Boolean);
  if (!REAL_SCHEMA_CONTRACT
      || REAL_SCHEMA_CONTRACT.database_name !== 'winex_aima'
      || REAL_SCHEMA_CONTRACT.schema_name !== 'dbo') {
    throw new Error('缺少 winex_aima.dbo 只读结构契约，请先运行 capture-real-schema-contract.mjs');
  }
  const tableContracts = Object.fromEntries(targetTables.map(table => {
    const contract = REAL_SCHEMA_CONTRACT.tables?.[table];
    if (!contract || !Array.isArray(contract.columns)
        || !/^[a-f0-9]{64}$/.test(String(contract.fingerprint_sha256 || ''))) {
      throw new Error(`真实库结构契约缺少目标表：${table}`);
    }
    return [table, contract];
  }));
  const fingerprints = Object.fromEntries(
    Object.entries(tableContracts).map(([table, contract]) => [
      table, contract.fingerprint_sha256,
    ]),
  );
  return {
    schema_version: 'profile-extraction-contract-v1',
    route: eventNo ? 'EVENT' : 'TABLE_DOMAIN',
    event_no: eventNo,
    event_table: eventTable,
    dependency_tables: dependencies,
    target_tables: targetTables,
    allowed_result_fields: Object.fromEntries(
      Object.entries(tableContracts).map(([table, contract]) => [
        table, contract.columns.map(column => column.name),
      ]),
    ),
    target_schema_fingerprints: fingerprints,
    target_structure_fingerprint: sha256(JSON.stringify(fingerprints)),
    start_parameter: 'startTime',
    end_parameter: 'endTime',
    hospital_parameter: 'hospitalSOID',
    schema_name: 'dbo',
    database_name: 'winex_aima',
  };
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
  const sqlCapabilities = {
    source_extract: validateSqlCapability('source_extract', profile.sqlSource),
    overview: validateSqlCapability('overview', profile.sqlOverview),
    department_detail: validateSqlCapability('department_detail', profile.sqlDepartment),
    patient_detail: validateSqlCapability('patient_detail', profile.sqlPatientDetail),
  };
  const overviewMappingCandidates = resultMappingCandidates(profile.sqlOverview || '');
  const unit = mapUnit(profile.meta['计量单位'], indicator);
  const valueType = unit === 'ratio'
    ? 'rate_ratio'
    : /中位数/.test(indicator.formula || '') ? 'median_duration' : 'percentage';
  const targetMatch = String(profile.meta['目标值'] || '').match(/-?\d+(?:\.\d+)?/);
  const targetValue = targetMatch ? Number(targetMatch[0]) : null;
  const targetDirection = mapDirection(profile.meta['指标导向']) === 'lower_is_better'
    ? '<=' : '>=';
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
    extraction_contract: extractionContract(indicator, profile),
    result_contract: {
      value_type: valueType,
      unit,
      target_value: targetValue,
      target_direction: targetDirection,
    },
    numerator_rule: numeratorRule,
    numerator_caliber: profile.numeratorCaliber,
    denominator_rule: denominatorRule,
    denominator_caliber: profile.denominatorCaliber,
    configurable_parameters: profile.configurableParams,
    declared_parameters: declaredParameters(profile),
    parameter_contract: Object.fromEntries(
      declaredParameters(profile).map(name => {
        const canonicalName = PARAMETER_ALIASES.get(name) || name;
        return [name, {
          canonical_name: canonicalName,
          type: /time|at$/i.test(name) ? 'datetime' : 'unknown',
          source: ['start_time', 'end_time'].includes(canonicalName)
            ? 'stat_period'
            : 'profile_mapping',
        }];
      }),
    ),
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
    result_mapping_candidates: overviewMappingCandidates,
    sql_capabilities: sqlCapabilities,
    // 双库查询必须由目标医院验证同构对象和比较键后显式开启。生成器绝不根据
    // SQL 文本猜测两库兼容，从而避免尚未验证的 Profile 被误用于生产比较。
    dual_database_contract: {
      schema_version: 'dual-database-execution-contract-v1',
      schema_compatible: false,
      verified_source_roles: [],
      business_source_role: 'business',
      real_source_role: 'real',
      sql_hashes: {
        business: Object.fromEntries(Object.entries(sqlCapabilities)
          .filter(([, gate]) => gate.normalized_sha256)
          .map(([capability, gate]) => [capability, gate.normalized_sha256])),
        real: Object.fromEntries(Object.entries(sqlCapabilities)
          .filter(([, gate]) => gate.normalized_sha256)
          .map(([capability, gate]) => [capability, gate.normalized_sha256])),
      },
      source_verification: {
        business: { metadata_status: 'unverified', compile_status: 'unverified' },
        real: { metadata_status: 'unverified', compile_status: 'unverified' },
      },
      overview_result_mapping: {
        ...(valueType === 'rate_ratio' ? {
          index_value: overviewMappingCandidates.index_value || '',
          component_left: overviewMappingCandidates.component_left || '',
          component_right: overviewMappingCandidates.component_right || '',
        } : valueType === 'median_duration' ? {
          index_value: overviewMappingCandidates.index_value || '',
          sample_count: overviewMappingCandidates.sample_count || '',
          target_value: overviewMappingCandidates.target_value || '',
        } : {
          numerator_count: overviewMappingCandidates.numerator_count || '',
          denominator_count: overviewMappingCandidates.denominator_count || '',
        }),
        ...(overviewMappingCandidates.target_value ? {
          target_value: overviewMappingCandidates.target_value,
        } : {}),
      },
      department_comparison_key: '',
      patient_comparison_key: '',
      numerator_classification_field: '',
      department_compare_fields: [],
      patient_compare_fields: [],
      allowed_compare_fields: [],
      verification_blockers: [
        'business_and_real_schema_not_verified',
        'comparison_keys_not_verified',
      ],
    },
    field_contract: {
      business_fields: {},
    },
    field_mapping: {
      status: 'missing',
      dialect: 'sqlserver',
      main_table: profile.meta['中间表'] === '—' ? '' : (profile.meta['中间表'] || ''),
      fields: {},
      parameters: {},
      relations: [],
      query_profile: '',
      source_roles: {
        business: {
          source_id: 'winex_all_dev',
          database_name: 'WiNEX_All_DEV',
          status: 'unverified',
        },
        real: {
          source_id: 'winex_aima',
          database_name: 'winex_aima',
          status: 'unverified',
        },
      },
    },
  };
}

function runtimeManifest(indicator) {
  const profiles = indicator.profiles.map(profile => runtimeProfile(indicator, profile));
  const defaultProfile = profiles.find(profile => profile.execution_status !== 'draft');
  const formula = indicator.ruleId === 'HXZD-012-001'
    ? [
      '四级手术并发症发生率 = 四级手术并发症患者数 ÷ 四级手术患者数',
      '三级手术并发症发生率 = 三级手术并发症患者数 ÷ 三级手术患者数',
      '最终结果 = 四级手术并发症发生率 ÷ 三级手术并发症发生率',
      '三级手术并发症发生率为0时结果为无样本；同一患者三级、四级手术均发生并发症时按审批口径归入四级',
    ].join('\n')
    : indicator.ruleId === 'HXZD-012-002'
      ? [
        '四级手术患者死亡率 = 四级手术死亡患者数 ÷ 四级手术患者数',
        '三级手术患者死亡率 = 三级手术死亡患者数 ÷ 三级手术患者数',
        '最终结果 = 四级手术患者死亡率 ÷ 三级手术患者死亡率',
        '三级手术患者死亡率为0时结果为无样本',
      ].join('\n')
      : indicator.formula;
  return {
    schema_version: 'hxzd-runtime-v2',
    rule_id: indicator.ruleId,
    rule_name: indicator.title,
    category: indicator.systemName,
    definition: indicator.definition,
    formula,
    note: indicator.note,
    significance: indicator.significance,
    unit: mapUnit(indicator.profiles[0]?.meta['计量单位'], indicator),
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
    ['source_extract', 'etl_source.sql', profile.sqlSource],
    ['overview', 'overview.sql', profile.sqlOverview],
    ['department_detail', 'department.sql', profile.sqlDepartment],
    ['patient_detail', 'patient_detail.sql', profile.sqlPatientDetail],
  ];
  const result = [];
  for (const [capability, fileName, sql] of items) {
    if (!sql || !sql.trim()) continue;
    const normalized = normalizeSqlWithCorrections(sql);
    writePage(join(directory, fileName), normalized.sql + '\n');
    for (const correction of normalized.corrections) {
      result.push({
        rule_id: indicator.ruleId,
        profile_id: profileId,
        capability,
        sql_path: `sql-specs/${indicator.ruleId}/profiles/${profileId}/${fileName}`,
        raw_sha256: sha256(sql),
        execution_sha256: sha256(normalized.sql),
        ...correction,
      });
    }
  }
  return result;
}

// ─── 生成：指标主页面 ────────────────────────────────────────────────────────────
function generateIndicatorPage(indicator) {
  const manifest = runtimeManifest(indicator);
  const defaultProfile = manifest.default_profile || '';

  const aliases = [];
  for (const p of indicator.profiles) {
    const alias = validSearchTerm(p.meta['指标名称别名']);
    if (alias) aliases.push(alias);
  }

  const keywords = extractKeywords(indicator);
  const direction = mapDirection(indicator.profiles[0]?.meta['指标导向']);
  const unit = mapUnit(indicator.profiles[0]?.meta['计量单位'], indicator);

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

function mapUnit(raw, indicator = null) {
  if (!raw) return 'percentage';
  if (raw.includes('百分比') || raw.includes('%')) return 'percentage';
  const target = String(indicator?.profiles?.[0]?.meta?.['目标值'] || '');
  if (raw.includes('分钟') || target.includes('分钟')
      || (raw.includes('数值') && /报告时间|中位数/.test(indicator?.formula || ''))) {
    return 'minutes';
  }
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
  keywords.add(validSearchTerm(indicator.systemName));
  for (const p of indicator.profiles) {
    const time = validSearchTerm(p.meta['时间维度']);
    const event = validSearchTerm(p.meta['事件名称'] || p.meta['关联事件']);
    if (time) keywords.add(time);
    if (event) keywords.add(event);
  }
  return uniqueSearchTerms([...keywords]).slice(0, 12);
}

function characterNgrams(value, size = 2) {
  const normalized = String(value || '')
    .toLowerCase()
    .replace(/[\s\p{P}\p{S}_]+/gu, '');
  const result = new Set();
  for (let index = 0; index <= normalized.length - size; index++) {
    result.add(normalized.slice(index, index + size));
  }
  return [...result];
}

function retrievalCard(indicator, manifest, aliases, keywords) {
  const defaultProfile = manifest.profiles.find(
    profile => profile.profile_id === manifest.default_profile,
  ) || manifest.profiles[0] || {};
  return {
    rule_id: indicator.ruleId,
    rule_name: indicator.title,
    aliases,
    keywords,
    system_id: indicator.systemId,
    system_name: indicator.systemName,
    definition_short: compactText(indicator.definition),
    formula_short: compactText(indicator.formula),
    numerator_short: compactText(defaultProfile.numerator_rule),
    denominator_short: compactText(defaultProfile.denominator_rule),
    time_dimension: defaultProfile.time_dimension || '',
    default_profile_id: manifest.default_profile,
    execution_status: defaultProfile.execution_status || 'documentation_only',
    execution_blockers: defaultProfile.execution_blockers || [],
  };
}

function addNgramIndex(index, card) {
  const texts = [card.rule_name, card.system_name, ...card.aliases, ...card.keywords];
  for (const gram of uniqueSearchTerms(texts).flatMap(value => characterNgrams(value))) {
    if (!index[gram]) index[gram] = [];
    if (!index[gram].includes(card.rule_id)) index[gram].push(card.rule_id);
  }
}

function validateParsedContent(content, catalog, sections, indicators, sourceSqlCountOverride = null) {
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
  const sourceSqlCount = sourceSqlCountOverride == null
    ? [...content.matchAll(/```sql\s*\r?\n[\s\S]*?```/g)].length
    : sourceSqlCountOverride;
  const errors = [];

  if (catalog.length === 0) errors.push('指标目录不能为空');
  if (sections.length !== catalog.length) {
    errors.push(`指标目录与章节数量不一致：目录${catalog.length}项、章节${sections.length}项`);
  }
  if (indicators.length !== catalog.length) {
    errors.push(`指标目录与成功解析数量不一致：目录${catalog.length}项、解析${indicators.length}项`);
  }
  const declaredProfiles = catalog.reduce((sum, item) => sum + item.profileCount, 0);
  if (profileCount !== declaredProfiles) {
    errors.push(`目录声明${declaredProfiles}个Profile，实际解析${profileCount}个`);
  }
  if (sourceSqlCount !== sqlCount) {
    errors.push(`SQL块未完整归属Profile：原文${sourceSqlCount}个、解析${sqlCount}个`);
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
      if (!profile.numerator || !profile.denominator) {
        const nonRatio = /中位数|不按分子|不适用/.test([
          indicator.formula, profile.numerator, profile.denominator,
        ].join(' '));
        if (!nonRatio) errors.push(`${profileId}缺少分子或分母定义`);
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
    'retrieval_cards.json',
    'ngram_index.json',
  ];
  const missing = requiredIndexes.filter(fileName =>
    !existsSync(join(outputRoot, 'indexes', fileName)));
  if (!existsSync(join(outputRoot, 'sql-correction-manifest.json'))) {
    missing.push('sql-correction-manifest.json');
  }
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

function collectFileHashes(root, relative = '') {
  const result = {};
  const current = join(root, relative);
  for (const name of readdirSync(current).sort()) {
    const childRelative = relative ? `${relative}/${name}` : name;
    const child = join(root, childRelative);
    if (statSync(child).isDirectory()) {
      Object.assign(result, collectFileHashes(root, childRelative));
    } else if (childRelative !== 'release-manifest.json') {
      result[childRelative.replaceAll('\\', '/')] = sha256(readFileSync(child));
    }
  }
  return result;
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
    'sql-correction-manifest.json',
    'release-manifest.json',
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
  const options = parseArgs();
  const { inputPath, checkOnly } = options;
  console.log(`📖 读取输入文件: ${inputPath}`);

  if (!existsSync(inputPath)) {
    console.error(`❌ 文件不存在: ${inputPath}`);
    process.exit(1);
  }

  const content = readFileSync(inputPath, 'utf-8');
  let catalog;
  let sections;
  let indicators;
  let sourceSqlCount = null;
  if (inputPath.toLowerCase().endsWith('.json')) {
    const parsed = parseKnowledgeDraft(content);
    ({ catalog, sections, indicators, sourceSqlCount } = parsed);
    console.log('🧩 使用KnowledgeDraftV2机器契约');
  } else {
    const lines = content.split('\n');
    catalog = parseCatalog(lines);
    if (catalog.length === 0) {
      console.error('❌ 未能解析到指标目录表，请检查输入文件格式');
      process.exit(1);
    }
    const sectionRegex = /^##\s*(\d+)\.\s*(.+)$/gm;
    sections = [];
    let sm;
    while ((sm = sectionRegex.exec(content)) !== null) {
      sections.push({ seq: parseInt(sm[1]), title: sm[2].trim(), start: sm.index });
    }
    for (let i = 0; i < sections.length; i++) {
      const end = i + 1 < sections.length ? sections[i + 1].start : content.length;
      sections[i].text = content.slice(sections[i].start, end);
    }
    indicators = [];
    for (const sec of sections) {
      const catalogEntry = catalog.find(c => c.seq === sec.seq);
      if (!catalogEntry) continue;
      indicators.push(parseIndicatorSection(sec.text, catalogEntry));
    }
  }

  console.log(`📋 解析到 ${catalog.length} 项指标`);
  console.log(`📑 切分到 ${sections.length} 个指标 section`);
  console.log(`✅ 成功解析 ${indicators.length} 项指标`);
  validateParsedContent(content, catalog, sections, indicators, sourceSqlCount);
  if (checkOnly) {
    const profileCount = indicators.reduce((sum, item) => sum + item.profiles.length, 0);
    const sqlCount = indicators.reduce((sum, item) => sum + item.profiles.reduce(
      (value, profile) => value + [
        profile.sqlSource, profile.sqlOverview, profile.sqlDepartment, profile.sqlPatientDetail,
      ].filter(Boolean).length, 0), 0);
    console.log(`✅ 输入文件通过契约校验：${indicators.length}项指标、${profileCount}个Profile、${sqlCount}个SQL块`);
    return;
  }

  // 4. 先写入临时目录；只有完整校验通过后才替换正式知识库。
  const outputRoot = options.outputRoot || join(WIKI_ROOT, `.wiki-build-staging-${process.pid}`);
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
  const retrievalCards = [];
  const ngramIndex = {};
  const sqlCorrections = [];
  const sourceRelative = options.sourcePath
    || inputPath.replace(`${WIKI_ROOT}\\`, '').replaceAll('\\', '/');

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
      sqlCorrections.push(...writeProfileSql(outputRoot, indicator, profile));
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
    const aliases = uniqueSearchTerms(indicator.profiles
      .map(profile => profile.meta['指标名称别名'])
      .filter(Boolean));
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
      source_path: sourceRelative,
    });
    profileIndex[indicator.ruleId] = manifest.profiles.map(profile => ({
      profile_id: profile.profile_id,
      profile_name: profile.profile_name,
      governance_status: profile.governance_status,
      execution_status: profile.execution_status,
    }));
    relationIndex[indicator.ruleId] = { relations: [] };
    const keywords = extractKeywords(indicator);
    const card = retrievalCard(indicator, manifest, aliases, keywords);
    retrievalCards.push(card);
    addNgramIndex(ngramIndex, card);

    // 别名索引
    aliasIndex[indicator.title] = [indicator.ruleId];
    for (const p of indicator.profiles) {
      const alias = validSearchTerm(p.meta['指标名称别名']);
      if (alias) {
        if (!aliasIndex[alias]) aliasIndex[alias] = [];
        if (!aliasIndex[alias].includes(indicator.ruleId)) {
          aliasIndex[alias].push(indicator.ruleId);
        }
      }
    }

    // 关键词索引
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
  const releaseId = options.releaseId
    || `KB-${today().replaceAll('-', '')}-${sha256(content).slice(0, 12)}`;
  ensureDir(join(outputRoot, 'indexes'));
  writeFileSync(join(outputRoot, 'indexes', 'indicator_index.json'), JSON.stringify(indicatorIndex, null, 2), 'utf-8');
  writeFileSync(join(outputRoot, 'indexes', 'alias_index.json'), JSON.stringify(aliasIndex, null, 2), 'utf-8');
  writeFileSync(join(outputRoot, 'indexes', 'keyword_index.json'), JSON.stringify(keywordIndex, null, 2), 'utf-8');
  writeFileSync(join(outputRoot, 'indexes', 'profile_index.json'), JSON.stringify(profileIndex, null, 2), 'utf-8');
  writeFileSync(join(outputRoot, 'indexes', 'rule_index.json'), JSON.stringify({
    schema_version: 'hxzd-runtime-v2',
    release_id: releaseId,
    generated_at: today(),
    rules: ruleIndex,
  }, null, 2), 'utf-8');
  writeFileSync(join(outputRoot, 'indexes', 'hospital_override_index.json'), JSON.stringify({
    schema_version: 'hxzd-runtime-v2',
    release_id: releaseId,
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
  writeFileSync(join(outputRoot, 'indexes', 'retrieval_cards.json'), JSON.stringify({
    schema_version: 'hxzd-retrieval-v2',
    release_id: releaseId,
    cards: retrievalCards,
  }, null, 2), 'utf-8');
  writeFileSync(join(outputRoot, 'indexes', 'ngram_index.json'), JSON.stringify({
    schema_version: 'hxzd-retrieval-v2',
    release_id: releaseId,
    gram_size: 2,
    entries: ngramIndex,
  }, null, 2), 'utf-8');
  writeFileSync(join(outputRoot, 'sql-correction-manifest.json'), JSON.stringify({
    schema_version: 'sql-correction-manifest-v1',
    release_id: releaseId,
    requires_human_confirmation: sqlCorrections.length > 0,
    correction_count: sqlCorrections.length,
    corrections: sqlCorrections,
  }, null, 2), 'utf-8');

  // 8. 生成总索引页
  writeFileSync(join(outputRoot, 'index.md'), generateIndexPage(systems), 'utf-8');
  validateStaging(outputRoot, indicators);
  const totalProfiles = indicators.reduce((sum, i) => sum + i.profiles.length, 0);
  const totalSql = indicators.reduce((sum, indicator) => sum + indicator.profiles.reduce(
    (value, profile) => value + [
      profile.sqlSource, profile.sqlOverview, profile.sqlDepartment, profile.sqlPatientDetail,
    ].filter(Boolean).length, 0), 0);
  writeFileSync(join(outputRoot, 'release-manifest.json'), JSON.stringify({
    schema_version: 'knowledge-release-v2',
    release_id: releaseId,
    scope: 'company',
    generated_at: new Date().toISOString(),
    source_path: sourceRelative,
    source_sha256: sha256(content),
    generator_version: 'build-wiki-v2',
    model_id: options.modelId || 'deterministic-adapter',
    prompt_version: 'knowledge-release-normalizer-v1',
    prompt_sha256: existsSync(join(WIKI_ROOT, 'prompts', 'knowledge-release-normalizer.md'))
      ? sha256(readFileSync(join(WIKI_ROOT, 'prompts', 'knowledge-release-normalizer.md')))
      : null,
    counts: {
      indicators: indicators.length,
      profiles: totalProfiles,
      sql_blocks: totalSql,
    },
    files: collectFileHashes(outputRoot),
  }, null, 2), 'utf-8');
  if (!options.outputRoot) replaceGeneratedContent(outputRoot);

  // 9. 统计输出
  console.log('');
  console.log('═══════════════════════════════════════════');
  console.log('  Wiki 知识库生成完成');
  console.log('═══════════════════════════════════════════');
  console.log(`  指标页面：${indicators.length}`);
  console.log(`  口径 Profile：${totalProfiles}`);
  console.log(`  制度页面：${Object.keys(systems).length}`);
  console.log(`  SQL 规格：${indicators.filter(i => i.profiles.length > 0).length}`);
  console.log(`  SQL块：${totalSql}`);
  console.log(`  索引文件：10`);
  console.log(`  发布编号：${releaseId}`);
  if (options.outputRoot) console.log(`  候选目录：${outputRoot}`);
  console.log('═══════════════════════════════════════════');
}

main();
