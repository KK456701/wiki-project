// @vitest-environment jsdom

import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import StandardDataConfirmationEditor from '../src/components/standard-diagnosis/StandardDataConfirmationEditor.vue'

const baseProps = {
  selectedRows: [] as Array<{ rowKey: string; label: string; recordId: string; sourceGroup: string }>,
  departmentOptions: [
    { field: 'CURRENT_DEPT_ID', value: 'D1', label: '骨伤一科（D1）', denominatorCount: 8, numeratorCount: 2 },
    { field: 'CURRENT_DEPT_ID', value: 'D2', label: '康复科（D2）', denominatorCount: 3, numeratorCount: 0 },
  ],
  selectedDepartments: [] as string[],
  underTargetType: 'RECORD' as const,
  underRecordIds: '',
  underDepartments: [] as string[],
  underDepartmentManual: '',
  overNote: '',
  underNote: '',
}

describe('StandardDataConfirmationEditor', () => {
  it('shows concrete cross-page selections and preserves both issue directions', () => {
    const wrapper = mount(StandardDataConfirmationEditor, {
      props: {
        ...baseProps,
        selectedRows: [
          { rowKey: 'ENCOUNTER_ID:E1', label: '张三 · 骨伤一科', recordId: 'E1', sourceGroup: 'denominator' },
          { rowKey: 'ENCOUNTER_ID:E2', label: '李四 · 骨伤一科', recordId: 'E2', sourceGroup: 'numerator' },
        ],
        overNote: '排除测试患者',
        underNote: '补回骨伤一科',
      },
    })
    expect(wrapper.text()).toContain('张三 · 骨伤一科')
    expect(wrapper.text()).toContain('来自分子明细')
    expect(wrapper.text()).not.toContain('确认结果正确并结束排查')
  })

  it('uses one clarification action for either or both directions', async () => {
    const wrapper = mount(StandardDataConfirmationEditor, {
      props: {
        ...baseProps,
        selectedRows: [{ rowKey: 'ENCOUNTER_ID:E1', label: '患者E1', recordId: 'E1', sourceGroup: 'denominator' }],
      },
    })
    expect(wrapper.findAll('button.clarify-button')).toHaveLength(1)
    await wrapper.get('button.clarify-button').trigger('click')
    expect(wrapper.emitted('clarify')?.[0]).toEqual([])
    await wrapper.setProps({ selectedRows: [], underNote: '就诊号 E9 应该出现，但当前明细没有。' })
    expect(wrapper.get('button.clarify-button').attributes('disabled')).toBeUndefined()
    await wrapper.get('button.clarify-button').trigger('click')
    expect(wrapper.emitted('clarify')?.[1]).toEqual([])
    expect(wrapper.text()).not.toContain('进入链路核查')
  })

  it('shows both completed directions together in the unified result area', () => {
    const wrapper = mount(StandardDataConfirmationEditor, {
      props: {
        ...baseProps,
        selectedRows: [{ rowKey: 'ENCOUNTER_ID:E1', label: '患者E1', recordId: 'E1', sourceGroup: 'numerator' }],
        underNote: '就诊号 E9 应进入分子但没有出现。',
        overClarification: { naturalLanguageExplanation: '患者E1当前确实位于分子明细。' },
        underClarification: { naturalLanguageExplanation: '就诊号E9当前不在分子明细。' },
      },
    })
    const results = wrapper.get('.unified-clarification-results')
    expect(results.text()).toContain('本次澄清结果')
    expect(results.text()).toContain('患者E1当前确实位于分子明细。')
    expect(results.text()).toContain('就诊号E9当前不在分子明细。')
    expect(results.findAll('.clarification-answer')).toHaveLength(2)
  })

  it('数据少了只提供一个填写框', () => {
    const wrapper = mount(StandardDataConfirmationEditor, { props: baseProps })
    expect(wrapper.find('.clarification-kind.is-under textarea').exists()).toBe(true)
    expect(wrapper.findAll('.clarification-kind.is-under textarea')).toHaveLength(1)
    expect(wrapper.find('.clarification-kind.is-under .under-target-tabs').exists()).toBe(false)
  })

  it('supports selecting multiple departments without using the detail filter', async () => {
    const wrapper = mount(StandardDataConfirmationEditor, { props: baseProps })
    const departmentChecks = wrapper.findAll('.clarification-kind.is-over .department-option-list input')
    await departmentChecks[0].setValue(true)
    expect(wrapper.emitted('update:selectedDepartments')?.[0]).toEqual([['D1']])
    await wrapper.setProps({ selectedDepartments: ['D1'] })
    await departmentChecks[1].setValue(true)
    expect(wrapper.emitted('update:selectedDepartments')?.[1]).toEqual([['D1', 'D2']])
  })

  it('removes one selected patient and can clear all patients', async () => {
    const wrapper = mount(StandardDataConfirmationEditor, {
      props: {
        ...baseProps,
        selectedRows: [
          { rowKey: 'ENCOUNTER_ID:E1', label: '患者E1', recordId: 'E1', sourceGroup: 'denominator' },
          { rowKey: 'ENCOUNTER_ID:E2', label: '患者E2', recordId: 'E2', sourceGroup: 'denominator' },
        ],
        overNote: '排除测试患者',
      },
    })
    await wrapper.get('button[aria-label="删除患者"]').trigger('click')
    expect(wrapper.emitted('removeSelection')?.[0]).toEqual(['ENCOUNTER_ID:E1'])
    await wrapper.findAll('.selected-scope-panel > header button').at(-1)?.trigger('click')
    expect(wrapper.emitted('clearSelection')).toHaveLength(1)
  })
})
