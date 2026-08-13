import { describe, expect, it } from 'vitest'
import { formatIndicatorFailure, isInitializationSystemFailure } from '../src/utils/indicatorFailure'

describe('formatIndicatorFailure', () => {
  it('超时不误报为缺表', () => {
    const text = formatIndicatorFailure('INIT_DATABASE_TIMEOUT', '业务库目录查询超时')
    expect(text).toContain('INIT_DATABASE_TIMEOUT')
    expect(text).toContain('查询超过配置时限')
    expect(text).not.toContain('确认缺表')
  })
  it('权限错误明确说明不是缺表', () => {
    expect(formatIndicatorFailure('INIT_METADATA_PERMISSION_DENIED', 'ORA-01031')).toContain('这不代表缺表')
  })
  it('保留未知错误的原始信息', () => {
    const text = formatIndicatorFailure('ORA-00942', 'table or view does not exist')
    expect(text).toContain('ORA-00942')
    expect(text).toContain('table or view does not exist')
  })
  it('区分系统异常与数据问题', () => {
    expect(isInitializationSystemFailure('INIT_DATABASE_TIMEOUT')).toBe(true)
    expect(isInitializationSystemFailure('INIT_METADATA_QUERY_FAILED')).toBe(true)
    expect(isInitializationSystemFailure('INIT_MISSING_TABLE')).toBe(false)
  })
})
