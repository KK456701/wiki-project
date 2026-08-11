// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import StandardDiagnosisStepper from '../src/components/standard-diagnosis/StandardDiagnosisStepper.vue'
import { buildDiagnosisSqlExport } from '../src/utils/standardDiagnosisExport'

describe('StandardDiagnosisStepper', () => {
  it('在任务创建前只允许选择指标页', () => {
    const wrapper = mount(StandardDiagnosisStepper, {
      props: { currentStep: 'selection', hasCase: false },
    })

    const buttons = wrapper.get('nav').findAll('button')
    expect(buttons).toHaveLength(3)
    expect(buttons[0].attributes('disabled')).toBeUndefined()
    expect(buttons.slice(1).every((button) => button.attributes('disabled') !== undefined)).toBe(true)
    expect(buttons[0].classes()).toContain('active')
  })

  it('任务创建后允许直接查看三页并发出确定性步骤事件', async () => {
    const wrapper = mount(StandardDiagnosisStepper, {
      props: { currentStep: 'data', hasCase: true },
    })

    const buttons = wrapper.get('nav').findAll('button')
    expect(buttons.every((button) => button.attributes('disabled') === undefined)).toBe(true)
    expect(buttons[1].classes()).toContain('active')

    await buttons[2].trigger('click')
    expect(wrapper.emitted('navigate')).toEqual([['lineage']])
  })
})

describe('buildDiagnosisSqlExport', () => {
  it('保留正式与候选 SQL 证据但移除连接和认证信息', () => {
    const content = buildDiagnosisSqlExport([{
      title: '源表抽取 SQL',
      sqlKind: 'SOURCE_EXTRACT',
      sqlHash: 'formal-hash',
      databaseRole: 'BUSINESS',
      tableNames: ['INPATIENT_ENCOUNTER'],
      templateSql: 'SELECT * FROM INPATIENT_ENCOUNTER WHERE ID=:id',
      sql: "SELECT * FROM INPATIENT_ENCOUNTER WHERE ID='1'\n-- jdbc:oracle:thin:@host/service\n-- password=secret",
    }], {
      layer: 'SOURCE_EXTRACT',
      candidateSqlHash: 'candidate-hash',
      candidateSqlExecutable: "SELECT * FROM INPATIENT_ENCOUNTER WHERE ID='2'",
    }, () => '业务库')

    expect(content).toContain('当前知识库正式模板 SQL')
    expect(content).toContain('候选 SQL')
    expect(content).toContain('formal-hash')
    expect(content).toContain('candidate-hash')
    expect(content).not.toContain('jdbc:oracle')
    expect(content).not.toContain('password=secret')
    expect(content).toContain('[已移除连接或认证信息]')
  })
})
