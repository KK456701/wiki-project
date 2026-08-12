import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'

import { toBatchResult } from '../src/stores/agent'

describe('batch report snapshot mapping', () => {
  it('uses the persisted profileName returned by reportSnapshot.tasks', () => {
    const result = toBatchResult({
      batchRunId: 'BATCH_001', ruleId: 'HXZD-011-001', ruleName: '非计划再手术率',
      profileId: 'DEFAULT', profileName: '默认口径', status: 'success', resultValue: 0.12,
    })
    expect(result.profileLabel).toBe('默认口径')
    expect(result.ruleId).toBe('HXZD-011-001')
    expect(result.status).toBe('success')
  })

  it('keeps the live SSE profileLabel when it is present', () => {
    const result = toBatchResult({
      ruleId: 'HXZD-008-001', profileLabel: '实时口径', profileName: '快照口径',
    })
    expect(result.profileLabel).toBe('实时口径')
  })
})

describe('batch summary detail loading', () => {
  it('does not automatically prewarm detail queries while batch results stream in', () => {
    const source = readFileSync(
      new URL('../src/components/BatchExecutiveSummary.vue', import.meta.url), 'utf8',
    )
    expect(source).not.toContain('fetchIndicatorDetails')
    expect(source).not.toContain('watch(visibleAttentionItems')
    expect(source).toContain('明细按需查询')
  })
})
