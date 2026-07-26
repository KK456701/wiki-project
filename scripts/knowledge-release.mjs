#!/usr/bin/env node
/**
 * 核心制度指标知识库离线发版器。
 *
 * 该脚本只操作版本化 Wiki 文件，不启动在线治理工作台，也不接触患者数据。
 * Prepare 永远生成待审核候选；Publish 必须显式传入 --confirmed。
 */
import {
  copyFileSync,
  cpSync,
  existsSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  renameSync,
  rmSync,
  statSync,
  writeFileSync,
} from 'node:fs';
import { createHash } from 'node:crypto';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));
const PROJECT_ROOT = resolve(SCRIPT_DIR, '..');
const WIKI_ROOT = join(PROJECT_ROOT, 'core-rules-wiki');
const BUILD_SCRIPT = join(SCRIPT_DIR, 'build-wiki-from-markdown.mjs');
const VALIDATE_SCRIPT = join(SCRIPT_DIR, 'validate-wiki-contract.mjs');

function parseArgs() {
  const values = {};
  const args = process.argv.slice(2);
  for (let index = 0; index < args.length; index++) {
    const key = args[index];
    if (!key.startsWith('--')) continue;
    const name = key.slice(2);
    if (name === 'confirmed') {
      values.confirmed = true;
    } else if (args[index + 1] && !args[index + 1].startsWith('--')) {
      values[name] = args[++index];
    }
  }
  values.action = String(values.action || '').toLowerCase();
  if (!values.action) throw new Error('必须提供--action prepare|validate|publish|reclaim|rollback');
  return values;
}

function sha256(value) {
  return createHash('sha256').update(value).digest('hex');
}

function ensureDir(path) {
  mkdirSync(path, { recursive: true });
}

function json(path) {
  return JSON.parse(readFileSync(path, 'utf-8'));
}

function writeJson(path, value) {
  ensureDir(dirname(path));
  writeFileSync(path, `${JSON.stringify(value, null, 2)}\n`, 'utf-8');
}

function atomicJson(path, value) {
  ensureDir(dirname(path));
  const temporary = `${path}.tmp-${process.pid}`;
  writeJson(temporary, value);
  renameSync(temporary, path);
}

function safeId(value, label) {
  const normalized = String(value || '').trim();
  if (!/^[A-Za-z0-9_-]{1,128}$/.test(normalized)) throw new Error(`${label}格式无效`);
  return normalized;
}

function safeChild(root, relativePath, label) {
  const normalizedRoot = resolve(root);
  const child = resolve(normalizedRoot, String(relativePath || ''));
  if (child !== normalizedRoot && !child.startsWith(`${normalizedRoot}\\`)
      && !child.startsWith(`${normalizedRoot}/`)) {
    throw new Error(`${label}路径越界：${relativePath}`);
  }
  return child;
}

function releaseId(source) {
  const now = new Date();
  const stamp = now.toISOString().replace(/\D/g, '').slice(0, 14);
  return `KB-${stamp}-${sha256(readFileSync(source)).slice(0, 12)}`;
}

function walk(root, current = root) {
  const result = [];
  for (const name of readdirSync(current).sort()) {
    const path = join(current, name);
    if (statSync(path).isDirectory()) result.push(...walk(root, path));
    else result.push(path);
  }
  return result;
}

function fileHashes(root) {
  return Object.fromEntries(walk(root)
    .filter(path => !path.endsWith('hospital-package.json'))
    .map(path => [relative(root, path).replaceAll('\\', '/'), sha256(readFileSync(path))]));
}

function refreshReleaseHashes(snapshot) {
  const manifestPath = join(snapshot, 'release-manifest.json');
  const manifest = json(manifestPath);
  manifest.files = Object.fromEntries(walk(snapshot)
    .filter(path => path !== manifestPath)
    .map(path => [relative(snapshot, path).replaceAll('\\', '/'), sha256(readFileSync(path))]));
  writeJson(manifestPath, manifest);
}

function validateSnapshot(snapshot) {
  const manifestPath = join(snapshot, 'release-manifest.json');
  if (!existsSync(manifestPath)) throw new Error('候选缺少release-manifest.json');
  const manifest = json(manifestPath);
  if (manifest.schema_version !== 'knowledge-release-v2') throw new Error('发布清单版本不受支持');
  for (const [file, expected] of Object.entries(manifest.files || {})) {
    const path = safeChild(snapshot, file, '发布文件');
    if (!existsSync(path)) throw new Error(`发布文件缺失：${file}`);
    const actual = sha256(readFileSync(path));
    if (actual !== expected) throw new Error(`发布文件哈希不一致：${file}`);
  }
  const ruleIndex = json(join(snapshot, 'indexes', 'rule_index.json'));
  const retrieval = json(join(snapshot, 'indexes', 'retrieval_cards.json'));
  if (ruleIndex.release_id !== retrieval.release_id) throw new Error('检索索引发布编号不一致');
  if ((ruleIndex.rules || []).length !== (retrieval.cards || []).length) {
    throw new Error('规则索引与检索卡数量不一致');
  }
  const checked = spawnSync(process.execPath, [
    VALIDATE_SCRIPT,
    '--root', snapshot,
    '--expected-indicators', String(manifest.counts?.indicators ?? ''),
    '--expected-profiles', String(manifest.counts?.profiles ?? ''),
    '--expected-sql', String(manifest.counts?.sql_blocks ?? ''),
  ], { cwd: PROJECT_ROOT, encoding: 'utf-8' });
  if (checked.status !== 0) {
    throw new Error(`Wiki机器契约校验失败：\n${checked.stderr || checked.stdout}`);
  }
  return manifest;
}

function prepare(args) {
  if (!args.input) throw new Error('Prepare必须提供--input');
  const input = resolve(PROJECT_ROOT, args.input);
  if (!existsSync(input)) throw new Error(`输入文件不存在：${input}`);
  if (!/\.(?:md|json)$/i.test(input)) {
    throw new Error('确定性构建器只接收Markdown或KnowledgeDraftV2 JSON；Excel请通过PowerShell入口规范化');
  }
  const scope = args.scope === 'hospital' ? 'hospital' : 'company';
  const hospitalId = scope === 'hospital' ? safeId(args['hospital-id'], '医院编号') : '';
  const id = args['release-id'] ? safeId(args['release-id'], '发布编号') : releaseId(input);
  const candidate = join(WIKI_ROOT, 'review', 'pending', id);
  if (existsSync(candidate)) throw new Error(`待审核候选已存在：${id}`);
  const snapshot = join(candidate, 'snapshot');
  ensureDir(join(candidate, 'source'));
  const sourceCopy = join(candidate, 'source', input.split(/[\\/]/).pop());
  copyFileSync(input, sourceCopy);
  const built = spawnSync(process.execPath, [
    BUILD_SCRIPT,
    '--input', relative(PROJECT_ROOT, input),
    '--output-root', relative(PROJECT_ROOT, snapshot),
    '--release-id', id,
    '--source-path', relative(WIKI_ROOT, sourceCopy).replaceAll('\\', '/'),
    '--model-id', args['model-id'] || 'deterministic-adapter',
  ], { cwd: PROJECT_ROOT, encoding: 'utf-8' });
  if (built.status !== 0) {
    rmSync(candidate, { recursive: true, force: true });
    throw new Error(`知识候选构建失败：\n${built.stderr || built.stdout}`);
  }
  const releaseManifest = json(join(snapshot, 'release-manifest.json'));
  const promptPath = join(WIKI_ROOT, 'prompts', 'knowledge-release-normalizer.md');
  const promptSha256 = existsSync(promptPath) ? sha256(readFileSync(promptPath)) : null;
  const companyPointer = join(WIKI_ROOT, 'pointers', 'company-current.json');
  const baseReleaseId = scope === 'hospital' && existsSync(companyPointer)
    ? json(companyPointer).release_id || null : null;
  releaseManifest.scope = scope;
  releaseManifest.hospital_id = hospitalId || null;
  releaseManifest.base_release_id = baseReleaseId;
  releaseManifest.model_id = args['model-id'] || 'deterministic-adapter';
  releaseManifest.prompt_version = 'knowledge-release-normalizer-v1';
  releaseManifest.prompt_sha256 = promptSha256;
  writeJson(join(snapshot, 'release-manifest.json'), releaseManifest);
  writeJson(join(candidate, 'candidate.json'), {
    schema_version: 'knowledge-candidate-v2',
    candidate_id: id,
    release_id: id,
    status: 'pending_review',
    scope,
    hospital_id: hospitalId || null,
    base_release_id: baseReleaseId,
    model_id: args['model-id'] || 'deterministic-adapter',
    prompt_version: 'knowledge-release-normalizer-v1',
    prompt_sha256: promptSha256,
    created_at: new Date().toISOString(),
    unresolved_questions: [],
  });
  validateSnapshot(snapshot);
  console.log(`候选已生成：${id}`);
  console.log(candidate);
}

function validate(args) {
  const id = safeId(args.candidate || args['release-id'], '候选编号');
  const candidateRoot = join(WIKI_ROOT, 'review', 'pending', id);
  const snapshot = join(candidateRoot, 'snapshot');
  if (args.verification) applyHospitalVerification(
    candidateRoot,
    snapshot,
    resolve(PROJECT_ROOT, args.verification),
  );
  const manifest = validateSnapshot(snapshot);
  console.log(`候选校验通过：${manifest.release_id}`);
}

/**
 * 将目标医院通过DBHub生成的验证摘要合并进医院候选。验证文件只携带对象哈希和
 * 结论，不携带患者行或查询结果；SQL哈希必须与候选完全一致，防止拿旧验证提升新SQL。
 */
function applyHospitalVerification(candidateRoot, snapshot, verificationPath) {
  if (!existsSync(verificationPath)) throw new Error(`医院验证文件不存在：${verificationPath}`);
  const candidate = json(join(candidateRoot, 'candidate.json'));
  if (candidate.scope !== 'hospital' || !candidate.hospital_id) {
    throw new Error('只有医院范围候选可以应用DBHub验证摘要');
  }
  const verification = json(verificationPath);
  if (verification.schema_version !== 'hospital-sql-verification-v1') {
    throw new Error('医院SQL验证摘要版本不受支持');
  }
  if (verification.hospital_id !== candidate.hospital_id
      || verification.base_release_id !== candidate.base_release_id) {
    throw new Error('医院SQL验证摘要的医院或基础版本与候选不一致');
  }
  const ruleIndex = json(join(snapshot, 'indexes', 'rule_index.json'));
  const runtimeByProfile = new Map();
  for (const rule of ruleIndex.rules || []) {
    const runtimePath = join(snapshot, rule.runtime_path);
    const runtime = json(runtimePath);
    for (const profile of runtime.profiles || []) {
      runtimeByProfile.set(profile.profile_id, { runtimePath, runtime, profile });
    }
  }
  const touched = new Set();
  for (const [profileId, verified] of Object.entries(verification.profiles || {})) {
    const target = runtimeByProfile.get(profileId);
    if (!target) throw new Error(`验证摘要引用未知Profile：${profileId}`);
    if (verified.rule_id !== target.runtime.rule_id) {
      throw new Error(`${profileId}的rule_id与候选不一致`);
    }
    target.profile.field_contract = verified.field_contract || {};
    target.profile.field_mapping = verified.field_mapping || {};
    target.profile.result_mapping = verified.result_mapping || {};
    for (const [capability, proof] of Object.entries(verified.capabilities || {})) {
      const gate = target.profile.sql_capabilities?.[capability];
      if (!gate || gate.status === 'missing') {
        throw new Error(`${profileId}没有可验证的${capability} SQL`);
      }
      if (gate.normalized_sha256 !== proof.normalized_sha256) {
        throw new Error(`${profileId}的${capability} SQL哈希与验证摘要不一致`);
      }
      if (proof.trial_validated && !proof.compile_validated
          || proof.compile_validated && !proof.metadata_validated
          || proof.human_confirmed && !proof.trial_validated) {
        throw new Error(`${profileId}的${capability}验证阶段顺序无效`);
      }
      gate.status = proof.human_confirmed ? 'executable'
        : proof.trial_validated ? 'trial_validated'
          : proof.compile_validated ? 'compile_validated'
            : proof.metadata_validated ? 'metadata_validated'
              : gate.status;
      gate.blockers = gate.status === 'executable' ? [] : gate.blockers;
      gate.verification = {
        hospital_id: candidate.hospital_id,
        verified_at: proof.verified_at || new Date().toISOString(),
        object_hash: proof.object_hash || null,
      };
    }
    applyDualDatabaseVerification(
      target.profile,
      verified.dual_database_contract,
      candidate.hospital_id,
    );
    const result = target.profile.result_mapping || {};
    const fields = target.profile.field_contract?.business_fields || {};
    const mapping = target.profile.field_mapping?.fields || {};
    const overviewExecutable = target.profile.sql_capabilities?.overview?.status === 'executable';
    const departmentExecutable =
      target.profile.sql_capabilities?.department_detail?.status === 'executable';
    const patientExecutable =
      target.profile.sql_capabilities?.patient_detail?.status === 'executable';
    const dualExecutable =
      target.profile.dual_database_contract?.schema_compatible === true;
    if (overviewExecutable && departmentExecutable && patientExecutable && dualExecutable
        && Object.keys(fields).length > 0 && Object.keys(mapping).length > 0
        && result.index_value && result.numerator_count && result.denominator_count) {
      target.profile.execution_status = 'executable';
      target.profile.execution_blockers = [];
    } else {
      target.profile.execution_status = 'documentation_only';
      target.profile.execution_blockers = [
        '目标医院尚未完成双库概览、科室明细、患者明细与结果契约验证',
      ];
    }
    touched.add(target.runtimePath);
  }
  if (touched.size === 0) throw new Error('医院SQL验证摘要没有包含任何Profile');
  for (const path of touched) {
    const runtime = [...runtimeByProfile.values()].find(item => item.runtimePath === path).runtime;
    writeJson(path, runtime);
  }
  const manifestPath = join(snapshot, 'release-manifest.json');
  const manifest = json(manifestPath);
  manifest.dbhub_verification = {
    schema_version: verification.schema_version,
    hospital_id: verification.hospital_id,
    base_release_id: verification.base_release_id,
    profile_count: Object.keys(verification.profiles || {}).length,
    applied_at: new Date().toISOString(),
    verification_sha256: sha256(readFileSync(verificationPath)),
  };
  writeJson(manifestPath, manifest);
  refreshReleaseHashes(snapshot);
  candidate.verification_sha256 = manifest.dbhub_verification.verification_sha256;
  candidate.verification_applied_at = manifest.dbhub_verification.applied_at;
  writeJson(join(candidateRoot, 'candidate.json'), candidate);
}

/**
 * 合并双库验证时只接受固定角色、验证状态、结果列与比较字段，不允许验证文件
 * 覆盖 SQL 引用或 SQL 哈希。这样医院验证只能提升当前候选里的同一份 SQL，
 * 不能借验证摘要替换实际执行对象。
 */
function applyDualDatabaseVerification(profile, proof, hospitalId) {
  if (!proof) return;
  const roles = new Set(proof.verified_source_roles || []);
  const sourceVerification = proof.source_verification || {};
  const sourceValidated = role => sourceVerification[role]?.metadata_status === 'validated'
    && sourceVerification[role]?.compile_status === 'validated';
  if (proof.schema_compatible !== true
      || !roles.has('business') || !roles.has('real')
      || !sourceValidated('business') || !sourceValidated('real')) {
    throw new Error(`${profile.profile_id}的双库元数据或编译验证未完成`);
  }
  const result = proof.overview_result_mapping || {};
  if (!String(result.numerator_count || '').trim()
      || !String(result.denominator_count || '').trim()) {
    throw new Error(`${profile.profile_id}缺少双库概览分子或分母结果列`);
  }
  const departmentKey = String(proof.department_comparison_key || '').trim();
  const patientKey = String(proof.patient_comparison_key || '').trim();
  const classification = String(proof.numerator_classification_field || '').trim();
  const allowed = new Set((proof.allowed_compare_fields || []).map(String));
  const requested = [
    ...(proof.department_compare_fields || []),
    ...(proof.patient_compare_fields || []),
    classification,
  ].map(String).filter(Boolean);
  if (!departmentKey || !patientKey || !classification || allowed.size === 0
      || requested.some(field => !allowed.has(field))) {
    throw new Error(`${profile.profile_id}的双库明细比较键或允许字段不完整`);
  }
  const current = profile.dual_database_contract || {};
  profile.dual_database_contract = {
    ...current,
    schema_compatible: true,
    verified_source_roles: ['business', 'real'],
    business_source_role: 'business',
    real_source_role: 'real',
    source_verification: {
      business: {
        metadata_status: 'validated',
        compile_status: 'validated',
      },
      real: {
        metadata_status: 'validated',
        compile_status: 'validated',
      },
    },
    overview_result_mapping: {
      numerator_count: String(result.numerator_count),
      denominator_count: String(result.denominator_count),
    },
    department_comparison_key: departmentKey,
    patient_comparison_key: patientKey,
    numerator_classification_field: classification,
    department_compare_fields: (proof.department_compare_fields || []).map(String),
    patient_compare_fields: (proof.patient_compare_fields || []).map(String),
    allowed_compare_fields: [...allowed],
    verification_blockers: [],
    verification: {
      hospital_id: hospitalId,
      verified_at: proof.verified_at || new Date().toISOString(),
    },
  };
}

function pointerPath(scope, hospitalId) {
  return scope === 'hospital'
    ? join(WIKI_ROOT, 'pointers', 'hospitals', `${hospitalId}-current.json`)
    : join(WIKI_ROOT, 'pointers', 'company-current.json');
}

function releasePath(scope, hospitalId, id) {
  return scope === 'hospital'
    ? join(WIKI_ROOT, 'releases', 'hospitals', hospitalId, id)
    : join(WIKI_ROOT, 'releases', 'company', id);
}

function publish(args) {
  if (!args.confirmed) throw new Error('Publish必须显式提供--confirmed');
  const id = safeId(args.candidate || args['release-id'], '候选编号');
  const candidateRoot = join(WIKI_ROOT, 'review', 'pending', id);
  const candidate = json(join(candidateRoot, 'candidate.json'));
  if (candidate.status !== 'pending_review') throw new Error('候选当前状态不允许发布');
  const snapshot = join(candidateRoot, 'snapshot');
  const manifest = validateSnapshot(snapshot);
  const scope = candidate.scope === 'hospital' ? 'hospital' : 'company';
  const hospitalId = scope === 'hospital' ? safeId(candidate.hospital_id, '医院编号') : '';
  const target = releasePath(scope, hospitalId, manifest.release_id);
  if (existsSync(target)) throw new Error(`发布版本已存在：${manifest.release_id}`);
  ensureDir(dirname(target));
  cpSync(snapshot, target, { recursive: true, errorOnExist: true });
  validateSnapshot(target);
  atomicJson(pointerPath(scope, hospitalId), {
    schema_version: 'knowledge-pointer-v1',
    scope,
    hospital_id: hospitalId || null,
    release_id: manifest.release_id,
    release_path: relative(WIKI_ROOT, target).replaceAll('\\', '/'),
    activated_at: new Date().toISOString(),
  });
  candidate.status = 'published';
  candidate.published_at = new Date().toISOString();
  writeJson(join(candidateRoot, 'candidate.json'), candidate);
  const approved = join(WIKI_ROOT, 'review', 'approved', id);
  ensureDir(dirname(approved));
  renameSync(candidateRoot, approved);
  console.log(`发布成功：${manifest.release_id}`);
}

function rollback(args) {
  const scope = args.scope === 'hospital' ? 'hospital' : 'company';
  const hospitalId = scope === 'hospital' ? safeId(args['hospital-id'], '医院编号') : '';
  const id = safeId(args['release-id'], '发布编号');
  const target = releasePath(scope, hospitalId, id);
  validateSnapshot(target);
  atomicJson(pointerPath(scope, hospitalId), {
    schema_version: 'knowledge-pointer-v1',
    scope,
    hospital_id: hospitalId || null,
    release_id: id,
    release_path: relative(WIKI_ROOT, target).replaceAll('\\', '/'),
    activated_at: new Date().toISOString(),
    rollback: true,
  });
  console.log(`已回滚到：${id}`);
}

function reclaim(args) {
  if (!args.input) throw new Error('Reclaim必须提供已解压的医院知识包目录');
  const root = resolve(PROJECT_ROOT, args.input);
  const packageFile = join(root, 'hospital-package.json');
  if (!existsSync(packageFile)) throw new Error('医院知识包缺少hospital-package.json');
  const pkg = json(packageFile);
  if (pkg.schema_version !== 'hospital-knowledge-package-v1') throw new Error('医院知识包版本不受支持');
  const hospitalId = safeId(pkg.hospital_id, '医院编号');
  for (const [file, expected] of Object.entries(pkg.files || {})) {
    const path = safeChild(root, file, '医院知识包文件');
    if (!existsSync(path) || sha256(readFileSync(path)) !== expected) {
      throw new Error(`医院知识包文件校验失败：${file}`);
    }
  }
  const currentPointerPath = pointerPath('company', '');
  if (!existsSync(currentPointerPath)) throw new Error('公司当前版本指针不存在，无法进行三方Diff');
  const currentPointer = json(currentPointerPath);
  const baseReleaseId = safeId(pkg.base_release_id, '基础版本编号');
  const currentReleaseId = safeId(currentPointer.release_id, '当前公司版本编号');
  const baseManifestPath = join(releasePath('company', '', baseReleaseId), 'release-manifest.json');
  const currentManifestPath = join(releasePath('company', '', currentReleaseId), 'release-manifest.json');
  if (!existsSync(baseManifestPath)) {
    throw new Error(`医院包依赖的公司基础版本不存在：${baseReleaseId}`);
  }
  const baseFiles = json(baseManifestPath).files || {};
  const currentFiles = existsSync(currentManifestPath) ? json(currentManifestPath).files || {} : {};
  const hospitalFiles = pkg.files || {};
  const paths = [...new Set([
    ...Object.keys(baseFiles),
    ...Object.keys(currentFiles),
    ...Object.keys(hospitalFiles),
  ])].sort();
  const differences = paths
    .map(path => {
      const base = baseFiles[path] || null;
      const current = currentFiles[path] || null;
      const hospital = hospitalFiles[path] || null;
      const companyChanged = current !== base;
      const hospitalChanged = hospital != null && hospital !== base;
      return {
        path,
        base_sha256: base,
        current_company_sha256: current,
        hospital_sha256: hospital,
        company_changed: companyChanged,
        hospital_changed: hospitalChanged,
        conflict: companyChanged && hospitalChanged && current !== hospital,
      };
    })
    .filter(item => item.company_changed || item.hospital_changed);
  const id = `RECLAIM-${hospitalId}-${new Date().toISOString().replace(/\D/g, '').slice(0, 14)}`;
  const target = join(WIKI_ROOT, 'review', 'pending', id);
  ensureDir(target);
  cpSync(root, join(target, 'hospital-package'), { recursive: true });
  writeJson(join(target, 'candidate.json'), {
    schema_version: 'knowledge-candidate-v2',
    candidate_id: id,
    status: 'pending_review',
    scope: 'hospital_reclaim',
    hospital_id: hospitalId,
    base_release_id: baseReleaseId,
    current_company_release_id: currentReleaseId,
    created_at: new Date().toISOString(),
    merge_policy: 'three_way_review_required',
    base_version_conflict: baseReleaseId !== currentReleaseId,
    conflict_count: differences.filter(item => item.conflict).length,
  });
  writeJson(join(target, 'three-way-diff.json'), {
    schema_version: 'hospital-knowledge-three-way-diff-v1',
    hospital_id: hospitalId,
    base_release_id: baseReleaseId,
    current_company_release_id: currentReleaseId,
    base_version_conflict: baseReleaseId !== currentReleaseId,
    differences,
  });
  console.log(`医院差异包已进入待审核区：${id}`);
}

function exportHospital(args) {
  const hospitalId = safeId(args['hospital-id'], '医院编号');
  if (!args.output) throw new Error('ExportHospital必须提供--output目录');
  const output = resolve(PROJECT_ROOT, args.output);
  if (existsSync(output)) throw new Error(`导出目录已存在：${output}`);
  ensureDir(output);
  const companyPointer = join(WIKI_ROOT, 'pointers', 'company-current.json');
  const hospitalPointer = pointerPath('hospital', hospitalId);
  const company = existsSync(companyPointer) ? json(companyPointer) : {
    release_id: 'legacy-current',
  };
  const hospital = existsSync(hospitalPointer) ? json(hospitalPointer) : null;
  const mappingCandidates = [
    hospital?.release_path
      ? join(WIKI_ROOT, hospital.release_path, 'hospital-mappings', hospitalId) : null,
    join(WIKI_ROOT, 'hospital-mappings', hospitalId),
  ].filter(Boolean);
  const mapping = mappingCandidates.find(path => existsSync(path));
  if (mapping) cpSync(mapping, join(output, 'hospital-mappings', hospitalId), { recursive: true });
  const verification = join(WIKI_ROOT, 'verification', hospitalId);
  if (existsSync(verification)) cpSync(verification, join(output, 'verification'), { recursive: true });
  writeJson(join(output, 'hospital-package.json'), {
    schema_version: 'hospital-knowledge-package-v1',
    hospital_id: hospitalId,
    base_release_id: company.release_id || 'legacy-current',
    hospital_release_id: hospital?.release_id || null,
    exported_at: new Date().toISOString(),
    contains_patient_data: false,
    files: fileHashes(output),
  });
  console.log(`医院知识差异包目录已生成：${output}`);
}

function main() {
  const args = parseArgs();
  const actions = { prepare, validate, publish, rollback, reclaim, 'export-hospital': exportHospital };
  const action = actions[args.action];
  if (!action) throw new Error(`不支持的Action：${args.action}`);
  action(args);
}

try {
  main();
} catch (error) {
  console.error(`知识库发版失败：${error.message}`);
  process.exit(1);
}
