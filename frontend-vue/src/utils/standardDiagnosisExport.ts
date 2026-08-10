type Dictionary = Record<string, unknown>

function list(value: unknown): string[] {
  return Array.isArray(value) ? value.map(String).filter(Boolean) : []
}

/**
 * 导出只保留排查需要的 SQL 与版本证据。即使上游误把连接信息拼进说明或 SQL，
 * 也会在浏览器生成文件前按整行移除，避免凭据进入实施交接文件。
 */
export function redactDiagnosisExportSecrets(value: string): string {
  return value.split(/\r?\n/).map((line) => {
    if (/jdbc:[a-z]+:/i.test(line)
      || /\b(?:password|passwd|api[-_ ]?key|authorization|access[-_ ]?token)\b/i.test(line)
      || /\bbearer\s+[a-z0-9._~-]+/i.test(line)) {
      return '-- [已移除连接或认证信息]'
    }
    return line
  }).join('\n')
}

export function buildDiagnosisSqlExport(
  nodes: Dictionary[],
  candidate: Dictionary,
  databaseLabel: (value: unknown) => string,
): string {
  const sections = nodes.filter((node) => String(node.sql || '').trim()).map((node) => [
    `-- ${String(node.title || 'SQL节点')}`,
    `-- 用途：${String(node.description || '未登记')}`,
    `-- SQL类型：${String(node.sqlKind || node.nodeType || '未登记')}`,
    `-- SQL哈希：${String(node.sqlHash || '未提供')}`,
    `-- 数据库侧：${databaseLabel(node.databaseRole)}`,
    `-- 涉及表：${list(node.tableNames).join('、') || '未登记'}`,
    '-- 当前知识库正式模板 SQL',
    String(node.templateSql || node.sql || ''),
    '',
    '-- 当前统计窗口可直接执行 SQL',
    String(node.sql || ''),
  ].join('\n')).join('\n\n')

  const candidateSql = String(candidate.candidateSqlExecutable || candidate.sql || '')
  const candidateSection = candidateSql ? [
    '', '', '-- 候选 SQL',
    `-- 修改层级：${String(candidate.layer || '未登记')}`,
    `-- 修改说明：${String(candidate.diffSummary || '未登记')}`,
    `-- 原 SQL 哈希：${String(candidate.originalSqlHash || '未提供')}`,
    `-- 候选 SQL 哈希：${String(candidate.candidateSqlHash || '未提供')}`,
    candidateSql,
  ].join('\n') : ''

  return redactDiagnosisExportSecrets(`${sections}${candidateSection}`)
}
