// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import SqlExecuteButton from '../src/components/SqlExecuteButton.vue'

const baseProps = {
  token: 'token',
  sql: 'SELECT * FROM MRAS_BUSINESS_TEST',
  databaseRole: 'REAL',
  ruleId: 'HXZD-TEST',
  profileId: 'HXZD-TEST',
  statStart: '2025-01-01 00:00:00',
  statEnd: '2026-01-01 00:00:00',
}

describe('SqlExecuteButton', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: vi.fn().mockResolvedValue(undefined) },
    })
  })

  afterEach(() => {
    document.body.innerHTML = ''
    vi.unstubAllGlobals()
    vi.useRealTimers()
  })

  it('缺少数据库角色时禁用并给出原因', () => {
    const wrapper = mount(SqlExecuteButton, { props: { ...baseProps, databaseRole: '' } })
    const button = wrapper.get('button')
    expect(button.attributes('disabled')).toBeDefined()
    expect(button.attributes('title')).toContain('无法确定')
  })

  it('成功后展示状态、分页、截断提示和实际 SQL', async () => {
    const rows = Array.from({ length: 21 }, (_, index) => ({ ROW_NO: index + 1 }))
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        executionId: 'SQLRUN_1', databaseRole: 'REAL', databaseLabel: 'SQL Server 中间库',
        status: 'COMPLETED', rowCount: 21, truncated: true, columns: ['ROW_NO'], rows,
        durationMs: 37, executedSql: baseProps.sql,
      }),
    }))
    const wrapper = mount(SqlExecuteButton, { props: baseProps, attachTo: document.body })

    await wrapper.get('button').trigger('click')
    expect(wrapper.get('button').text()).toContain('正在校验')
    await vi.advanceTimersByTimeAsync(100)
    await flushPromises()

    expect(document.body.textContent).toContain('SQL Server 中间库')
    expect(document.body.textContent).toContain('结果超过 200 行')
    expect(document.body.textContent).toContain('第 1 / 2 页')
    expect(document.body.querySelectorAll('tbody tr')).toHaveLength(20)
  })

  it('空结果仍显示查询成功', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        executionId: 'SQLRUN_2', databaseRole: 'BUSINESS', databaseLabel: 'Oracle 业务库',
        status: 'COMPLETED', rowCount: 0, truncated: false, columns: [], rows: [],
        durationMs: 8, executedSql: 'SELECT 1 FROM DUAL WHERE 1=0',
      }),
    }))
    const wrapper = mount(SqlExecuteButton, {
      props: { ...baseProps, databaseRole: 'BUSINESS' }, attachTo: document.body,
    })
    await wrapper.get('button').trigger('click')
    await vi.advanceTimersByTimeAsync(100)
    await flushPromises()
    expect(document.body.textContent).toContain('查询成功，没有返回数据')
  })

  it('失败时显示后端错误且忙碌期间不会重复提交', async () => {
    let rejectRequest: ((reason: Error) => void) | undefined
    const fetchMock = vi.fn().mockImplementation(() => new Promise((_, reject) => {
      rejectRequest = reject
    }))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(SqlExecuteButton, { props: baseProps, attachTo: document.body })
    const button = wrapper.get('button')
    await button.trigger('click')
    await button.trigger('click')
    await vi.advanceTimersByTimeAsync(100)
    expect(fetchMock).toHaveBeenCalledTimes(1)
    rejectRequest?.(new Error('查询超时（30 秒）'))
    await flushPromises()
    expect(document.body.textContent).toContain('SQL 执行失败')
    expect(document.body.textContent).toContain('查询超时（30 秒）')
  })
})
