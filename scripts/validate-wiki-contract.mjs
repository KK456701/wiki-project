#!/usr/bin/env node
/**
 * 校验生成后的HXZD Wiki机器契约，不连接模型、DBHub或医院数据库。
 *
 * 该脚本供本地验收和CI使用。它只读取文件，不修改知识库。
 */
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const PROJECT_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
function parseArgs() {
  const result = {};
  const values = process.argv.slice(2);
  for (let index = 0; index < values.length; index++) {
    if (values[index].startsWith('--') && values[index + 1]) {
      result[values[index].slice(2)] = values[++index];
    }
  }
  return result;
}

const options = parseArgs();
const WIKI_ROOT = options.root
  ? resolve(PROJECT_ROOT, options.root)
  : join(PROJECT_ROOT, 'core-rules-wiki');

function json(path) {
  return JSON.parse(readFileSync(path, 'utf-8'));
}

function fail(errors) {
  console.error(`❌ Wiki机器契约校验失败：\n- ${errors.join('\n- ')}`);
  process.exit(1);
}

const errors = [];
const ruleIndexPath = join(WIKI_ROOT, 'indexes', 'rule_index.json');
if (!existsSync(ruleIndexPath)) fail(['缺少 indexes/rule_index.json']);

const ruleIndex = json(ruleIndexPath);
const rules = Array.isArray(ruleIndex.rules) ? ruleIndex.rules : [];
if (!['hxzd-runtime-v1', 'hxzd-runtime-v2'].includes(ruleIndex.schema_version)) {
  errors.push(`schema_version无效：${ruleIndex.schema_version || '空'}`);
}
const expectedIndicators = options['expected-indicators'] ? Number(options['expected-indicators']) : null;
const expectedProfiles = options['expected-profiles'] ? Number(options['expected-profiles']) : null;
const expectedSql = options['expected-sql'] ? Number(options['expected-sql']) : null;
const releaseManifestPath = join(WIKI_ROOT, 'release-manifest.json');
const releaseManifest = existsSync(releaseManifestPath) ? json(releaseManifestPath) : null;
if (releaseManifest) {
  if (releaseManifest.schema_version !== 'knowledge-release-v2') {
    errors.push('release-manifest.json版本无效');
  }
  if (!releaseManifest.release_id || releaseManifest.release_id !== ruleIndex.release_id) {
    errors.push('发布清单与规则索引的release_id不一致');
  }
  if (!releaseManifest.model_id || !releaseManifest.prompt_version
      || !/^[a-f0-9]{64}$/.test(String(releaseManifest.prompt_sha256 || ''))) {
    errors.push('发布清单缺少模型或提示词版本信息');
  }
}
if (expectedIndicators != null && rules.length !== expectedIndicators) {
  errors.push(`规则索引应为${expectedIndicators}项，实际${rules.length}项`);
}

const ruleIds = new Set();
const profileIds = new Set();
let profileCount = 0;
for (const rule of rules) {
  if (!/^HXZD-\d{3}-\d{3}$/.test(rule.rule_id || '')) {
    errors.push(`规则编号格式无效：${rule.rule_id || '空'}`);
  }
  if (ruleIds.has(rule.rule_id)) errors.push(`规则编号重复：${rule.rule_id}`);
  ruleIds.add(rule.rule_id);
  const runtimePath = join(WIKI_ROOT, rule.runtime_path || '');
  if (!existsSync(runtimePath)) {
    errors.push(`${rule.rule_id}缺少runtime.json`);
    continue;
  }
  const runtime = json(runtimePath);
  if (runtime.rule_id !== rule.rule_id) errors.push(`${rule.rule_id}运行契约编号不一致`);
  const profiles = Array.isArray(runtime.profiles) ? runtime.profiles : [];
  profileCount += profiles.length;
  const eligibleProfiles = profiles.filter(profile => profile.execution_status !== 'draft');
  const selectedDefault = profiles.find(profile => profile.profile_id === runtime.default_profile);
  if (eligibleProfiles.length > 0 && !selectedDefault) {
    errors.push(`${rule.rule_id}存在可展示Profile但默认Profile不存在`);
  }
  if (selectedDefault?.execution_status === 'draft') {
    errors.push(`${rule.rule_id}不能把草稿Profile设为默认生效口径`);
  }
  if (eligibleProfiles.length === 0 && runtime.default_profile !== null) {
    errors.push(`${rule.rule_id}仅有草稿Profile时default_profile必须为空`);
  }
  for (const profile of profiles) {
    if (profileIds.has(profile.profile_id)) errors.push(`Profile编号重复：${profile.profile_id}`);
    profileIds.add(profile.profile_id);
    if (profile.governance_status === 'draft' && profile.execution_status !== 'draft') {
      errors.push(`${profile.profile_id}未实现方案不能开放执行`);
    }
    if (!String(profile.numerator_rule || '').trim()
        || !String(profile.denominator_rule || '').trim()) {
      errors.push(`${profile.profile_id}缺少分子/分母说明；非比例指标必须明确标记不适用`);
    }
    if (profile.execution_status === 'executable') {
      const fields = profile.field_contract?.business_fields || {};
      const mapping = profile.field_mapping?.fields || {};
      const result = profile.result_mapping || {};
      if (Object.keys(fields).length === 0 || Object.keys(mapping).length === 0) {
        errors.push(`${profile.profile_id}标记可执行但缺少字段契约`);
      }
      if (!result.index_value || !result.numerator_count || !result.denominator_count) {
        errors.push(`${profile.profile_id}标记可执行但缺少统一结果列映射`);
      }
      const overview = profile.sql_refs?.overview;
      if (!overview || !existsSync(join(WIKI_ROOT, overview))) {
        errors.push(`${profile.profile_id}标记可执行但缺少概览SQL`);
      }
      if (profile.sql_capabilities?.overview?.status !== 'executable') {
        errors.push(`${profile.profile_id}标记可执行但概览SQL未完成全部门禁`);
      }
    }
    for (const path of Object.values(profile.sql_refs || {}).filter(Boolean)) {
      if (!existsSync(join(WIKI_ROOT, path))) errors.push(`${profile.profile_id}缺少SQL文件：${path}`);
    }
    for (const [capability, gate] of Object.entries(profile.sql_capabilities || {})) {
      const allowed = [
        'missing', 'raw', 'normalized', 'static_validated', 'verification_required',
        'metadata_validated', 'compile_validated', 'trial_validated', 'executable',
      ];
      if (!allowed.includes(gate.status)) {
        errors.push(`${profile.profile_id}的${capability}校验状态无效：${gate.status}`);
      }
      if (gate.status === 'executable' && (gate.blockers || []).length > 0) {
        errors.push(`${profile.profile_id}的${capability}仍有阻断项却标记可执行`);
      }
    }
  }
}

if (expectedProfiles != null && profileCount !== expectedProfiles) {
  errors.push(`Profile应为${expectedProfiles}个，实际${profileCount}个`);
}
const manifestDirectories = readdirSync(join(WIKI_ROOT, 'sql-specs'), { withFileTypes: true })
  .filter(entry => entry.isDirectory()).length;
if (manifestDirectories !== rules.length) {
  errors.push(`SQL规格目录应与规则数一致，规则${rules.length}项、目录${manifestDirectories}个`);
}

const sqlCount = rules.reduce((total, rule) => {
  const runtime = json(join(WIKI_ROOT, rule.runtime_path));
  return total + (runtime.profiles || []).reduce(
    (sum, profile) => sum + Object.values(profile.sql_refs || {}).filter(Boolean).length, 0);
}, 0);
if (expectedSql != null && sqlCount !== expectedSql) {
  errors.push(`SQL引用应为${expectedSql}个，实际${sqlCount}个`);
}
if (releaseManifest) {
  if (releaseManifest.counts?.indicators !== rules.length
      || releaseManifest.counts?.profiles !== profileCount
      || releaseManifest.counts?.sql_blocks !== sqlCount) {
    errors.push('发布清单数量与机器契约实际数量不一致');
  }
}
const cardsPath = join(WIKI_ROOT, 'indexes', 'retrieval_cards.json');
if (ruleIndex.schema_version === 'hxzd-runtime-v2') {
  if (!existsSync(cardsPath)) {
    errors.push('v2知识库缺少retrieval_cards.json');
  } else {
    const cards = json(cardsPath);
    if (releaseManifest && cards.release_id !== releaseManifest.release_id) {
      errors.push('检索卡与发布清单的release_id不一致');
    }
    if ((cards.cards || []).length !== rules.length) errors.push('检索卡数量与规则索引不一致');
    for (const card of cards.cards || []) {
      if ((card.aliases || []).some(value => /^(?:—|-|无|暂无|n\/?a)$/i.test(String(value).trim()))) {
        errors.push(`${card.rule_id}检索卡包含无效别名`);
      }
    }
  }
}

if (errors.length > 0) fail(errors);
console.log(`✅ Wiki机器契约有效：${rules.length}项指标、${profileCount}个Profile、${sqlCount}个SQL引用`);
