const TIMEOUT_CODES = new Set(['INIT_DATABASE_TIMEOUT', 'DATABASE_TIMEOUT', 'HTTP_TIMEOUT', 'DBHUB_MCP_TIMEOUT'])
const CONNECTION_CODES = new Set(['INIT_DATABASE_UNAVAILABLE', 'DATABASE_UNAVAILABLE', 'CONNECTION_FAILED'])
const INITIALIZATION_SYSTEM_FAILURE_CODES = new Set([
  'INIT_DATABASE_TIMEOUT', 'INIT_DATABASE_UNAVAILABLE', 'INIT_METADATA_PERMISSION_DENIED',
  'INIT_METADATA_QUERY_FAILED', 'INIT_DATA_PROBE_FAILED', 'INIT_JOIN_PROBE_FAILED',
])

export function isInitializationSystemFailure(errorCode?: string): boolean {
  return INITIALIZATION_SYSTEM_FAILURE_CODES.has((errorCode || '').trim().toUpperCase())
}

function failureAdvice(code: string): string {
  if (TIMEOUT_CODES.has(code) || code.endsWith('_TIMEOUT')) return '本次查询超过配置时限，请检查数据库负载、网络或超时配置后重试。'
  if (CONNECTION_CODES.has(code) || code.endsWith('_UNAVAILABLE')) return '数据库连接不可用，请检查连接配置、网络和数据库服务状态后重试。'
  if (code === 'INIT_METADATA_PERMISSION_DENIED' || code.includes('PERMISSION')) return '当前账号无权读取数据库目录；这不代表缺表，请补充目录查询权限后重试。'
  if (code === 'INIT_METADATA_QUERY_FAILED') return '数据库目录查询失败，但不能据此判断缺表；请查看数据基础检查中的原始数据库错误。'
  if (code === 'INIT_MISSING_TABLE' || code.includes('MISSING_TABLE')) return '数据库目录已成功返回且确认缺表，请核对知识库依赖表名、Schema 与医院实际表结构。'
  if (code === 'INIT_MISSING_COLUMN' || code.includes('MISSING_COLUMN')) return '数据库目录已成功返回且确认缺字段，请核对知识库字段名与医院实际表结构。'
  return '请根据上述错误码和数据基础检查详情处理后重试，系统不会推测或补造指标值。'
}

export function formatIndicatorFailure(errorCode?: string, errorMessage?: string): string {
  const code = (errorCode || '').trim().toUpperCase()
  const detail = (errorMessage || '').trim() || '数据源或执行链路未完成，后端未返回具体原因。'
  return `计算失败${code ? `（${code}）` : ''}：${detail} ${failureAdvice(code)}`
}
