import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';

const projectRoot = resolve(import.meta.dirname, '..', '..');
const builder = join(projectRoot, 'scripts', 'build-wiki-from-markdown.mjs');
const source = join(
  projectRoot,
  'core-rules-wiki',
  'raw',
  'company',
  '35项核心制度指标完整提取.md',
);

function runBuilder(input, output) {
  return spawnSync(process.execPath, [
    builder,
    '--input', input,
    '--output-root', output,
    '--release-id', 'KB-NODE-TEST',
  ], { cwd: projectRoot, encoding: 'utf-8' });
}

function draft(sql, overrides = {}) {
  return {
    schema_version: 'knowledge-draft-v2',
    source: {
      file_name: 'sample.md',
      sha256: 'a'.repeat(64),
    },
    indicators: [{
      rule_id: 'HXZD-001-001',
      rule_name: '测试指标',
      system_id: 'HXZD-001',
      system_name: '测试制度',
      definition: '测试定义',
      formula: '测试公式',
      source_refs: ['sample.md:L1-L10'],
      confidence: 0.9,
      profiles: [{
        profile_name: '测试方案',
        numerator: '分子',
        denominator: '分母',
        sql_refs: { overview: 'SQL_BLOCK_0001' },
        source_refs: ['sample.md:L5-L10'],
        confidence: 0.9,
      }],
    }],
    sql_blocks: { SQL_BLOCK_0001: sql },
    unresolved_questions: [],
    ...overrides,
  };
}

test('当前来源稳定生成35项、45个Profile和169个SQL块', () => {
  const output = mkdtempSync(join(tmpdir(), 'hxzd-release-'));
  try {
    const result = runBuilder(source, output);
    assert.equal(result.status, 0, result.stderr || result.stdout);
    const manifest = JSON.parse(readFileSync(join(output, 'release-manifest.json'), 'utf-8'));
    assert.deepEqual(manifest.counts, {
      indicators: 35,
      profiles: 45,
      sql_blocks: 169,
    });
    const aliases = JSON.parse(readFileSync(join(output, 'indexes', 'alias_index.json'), 'utf-8'));
    assert.equal(Object.hasOwn(aliases, '—'), false);
    assert.equal(Object.hasOwn(aliases, '无'), false);
    const runtime = JSON.parse(readFileSync(
      join(output, 'sql-specs', 'HXZD-001-001', 'runtime.json'),
      'utf-8',
    ));
    const dual = runtime.profiles[0].dual_database_contract;
    assert.equal(dual.schema_version, 'dual-database-execution-contract-v1');
    assert.equal(dual.schema_compatible, false);
    assert.deepEqual(dual.verified_source_roles, []);
    assert.equal(dual.source_verification.business.metadata_status, 'unverified');
    assert.equal(
      dual.overview_result_mapping.numerator_count,
      '分子入院48小时内转科患者人次数',
    );
    assert.equal(
      dual.overview_result_mapping.denominator_count,
      '分母同期入院患者总人次数',
    );
    assert.ok(dual.verification_blockers.includes('business_and_real_schema_not_verified'));
    const corrections = JSON.parse(readFileSync(
      join(output, 'sql-correction-manifest.json'),
      'utf-8',
    ));
    assert.equal(corrections.schema_version, 'sql-correction-manifest-v1');
    assert.equal(corrections.release_id, 'KB-NODE-TEST');
    assert.equal(corrections.correction_count, 420);
    assert.equal(
      corrections.corrections.filter(item => item.type === 'dangling_where_and').length,
      129,
    );
    assert.ok(corrections.corrections.every(item => (
      item.raw_sha256 !== item.execution_sha256
      && item.before !== item.after
      && item.line >= 1
    )));
  } finally {
    rmSync(output, { recursive: true, force: true });
  }
});

test('模型丢失来源或SQL引用时拒绝构建', () => {
  const root = mkdtempSync(join(tmpdir(), 'hxzd-invalid-'));
  try {
    const input = join(root, 'draft.json');
    const output = join(root, 'release');
    const value = draft('SELECT 1 AS index_value');
    value.indicators[0].source_refs = [];
    writeFileSync(input, JSON.stringify(value), 'utf-8');
    const missingSource = runBuilder(input, output);
    assert.notEqual(missingSource.status, 0);
    assert.match(missingSource.stderr, /source_refs/);

    const lostSql = draft('SELECT 1 AS index_value', {
      sql_blocks: {
        SQL_BLOCK_0001: 'SELECT 1 AS index_value',
        SQL_BLOCK_0002: 'SELECT 2 AS numerator_count',
      },
    });
    writeFileSync(input, JSON.stringify(lostSql), 'utf-8');
    const missingReference = runBuilder(input, output);
    assert.notEqual(missingReference.status, 0);
    assert.match(missingReference.stderr, /丢失了1个SQL块引用/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('Excel错误和未知数据库函数保留文档但阻止SQL执行', () => {
  const root = mkdtempSync(join(tmpdir(), 'hxzd-sql-gate-'));
  try {
    const input = join(root, 'draft.json');
    const output = join(root, 'release');
    writeFileSync(input, JSON.stringify(draft(
      'SELECT #NAME? + hospital_udf(value) AS index_value FROM t',
    )), 'utf-8');
    const result = runBuilder(input, output);
    assert.equal(result.status, 0, result.stderr || result.stdout);
    const runtime = JSON.parse(readFileSync(
      join(output, 'sql-specs', 'HXZD-001-001', 'runtime.json'),
      'utf-8',
    ));
    const overview = runtime.profiles[0].sql_capabilities.overview;
    assert.equal(overview.status, 'verification_required');
    assert.ok(overview.blockers.some(value => value.includes('Excel')));
    assert.deepEqual(overview.unknown_functions, ['hospital_udf']);
    assert.equal(runtime.profiles[0].execution_status, 'documentation_only');
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('只修复允许的SQL模板错误且不改写参数名称', () => {
  const root = mkdtempSync(join(tmpdir(), 'hxzd-sql-correction-'));
  try {
    const input = join(root, 'draft.json');
    const output = join(root, 'release');
    const sql = [
      'SELECT COUNT(*) AS [分母]',
      'FROM dbo.encounter WITH #{NOLOCK}',
      'WHERE',
      '-- 保留这段业务说明',
      'AND hospital_soid = :hospital_soid',
      'AND admitted_at >= :startTime',
      'AND admitted_at < :marptEndAt',
    ].join('\n');
    writeFileSync(input, JSON.stringify(draft(sql)), 'utf-8');
    const result = runBuilder(input, output);
    assert.equal(result.status, 0, result.stderr || result.stdout);

    const executionSql = readFileSync(
      join(
        output,
        'sql-specs',
        'HXZD-001-001',
        'profiles',
        'HXZD-001-001-company-default',
        'overview.sql',
      ),
      'utf-8',
    );
    assert.match(executionSql, /WITH \(NOLOCK\)/);
    assert.doesNotMatch(executionSql, /WITH #\{NOLOCK\}/);
    assert.doesNotMatch(executionSql, /WHERE\s*--[^\r\n]*\r?\n\s*AND/i);
    assert.match(executionSql, /:startTime/);
    assert.match(executionSql, /:marptEndAt/);
    assert.doesNotMatch(executionSql, /:start_time|:end_time/);

    const runtime = JSON.parse(readFileSync(
      join(output, 'sql-specs', 'HXZD-001-001', 'runtime.json'),
      'utf-8',
    ));
    const profile = runtime.profiles[0];
    assert.equal(profile.parameter_contract.startTime.canonical_name, 'start_time');
    assert.equal(profile.parameter_contract.marptEndAt.canonical_name, 'end_time');
    assert.equal(
      profile.parameter_contract.hospital_soid.canonical_name,
      'hospital_scope_value',
    );
    assert.equal(profile.result_mapping_candidates.denominator_count, '分母');

    const manifest = JSON.parse(readFileSync(
      join(output, 'sql-correction-manifest.json'),
      'utf-8',
    ));
    assert.deepEqual(
      [...new Set(manifest.corrections.map(item => item.type))].sort(),
      ['dangling_where_and', 'invalid_nolock_placeholder'],
    );
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
