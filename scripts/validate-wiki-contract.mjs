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
const WIKI_ROOT = join(PROJECT_ROOT, 'core-rules-wiki');

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
if (ruleIndex.schema_version !== 'hxzd-runtime-v1') {
  errors.push(`schema_version无效：${ruleIndex.schema_version || '空'}`);
}
if (rules.length !== 35) errors.push(`规则索引应为35项，实际${rules.length}项`);

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
    }
    for (const path of Object.values(profile.sql_refs || {}).filter(Boolean)) {
      if (!existsSync(join(WIKI_ROOT, path))) errors.push(`${profile.profile_id}缺少SQL文件：${path}`);
    }
  }
}

if (profileCount !== 45) errors.push(`Profile应为45个，实际${profileCount}个`);
const manifestDirectories = readdirSync(join(WIKI_ROOT, 'sql-specs'), { withFileTypes: true })
  .filter(entry => entry.isDirectory()).length;
if (manifestDirectories !== 35) {
  errors.push(`SQL规格目录应为35个，实际${manifestDirectories}个`);
}

if (errors.length > 0) fail(errors);
console.log('✅ Wiki机器契约有效：35项指标、45个Profile，未验证Profile均未开放执行');
