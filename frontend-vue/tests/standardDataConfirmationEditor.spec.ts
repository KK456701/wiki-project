// @vitest-environment jsdom

import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import StandardDataConfirmationEditor from '../src/components/standard-diagnosis/StandardDataConfirmationEditor.vue'

describe('StandardDataConfirmationEditor', () => {
  it('restores saved clarification notes and keeps both issue directions', () => {
    const wrapper = mount(StandardDataConfirmationEditor, {
      props: { selectedCount: 2, overNote: '排除测试患者', underNote: '补回骨伤一科' },
    })
    expect(wrapper.findAll('textarea').map(item => item.element.value)).toEqual(['排除测试患者', '补回骨伤一科'])
    expect(wrapper.text()).toContain('已选 2 条')
    expect(wrapper.get('button.workspace-primary').attributes('disabled')).toBeUndefined()
  })

  it('emits over-only, under-only and no-issue actions', async () => {
    const wrapper = mount(StandardDataConfirmationEditor, {
      props: { selectedCount: 1, overNote: '', underNote: '' },
    })
    await wrapper.get('button.workspace-primary').trigger('click')
    expect(wrapper.emitted('submit')?.[0]).toEqual([{ noIssue: false, openLineage: false }])
    await wrapper.setProps({ selectedCount: 0, underNote: '缺少骨伤一科' })
    await wrapper.findAll('button')[1].trigger('click')
    expect(wrapper.emitted('submit')?.[1]).toEqual([{ noIssue: false, openLineage: true }])
    await wrapper.findAll('button').at(-1)!.trigger('click')
    expect(wrapper.emitted('submit')?.[2]).toEqual([{ noIssue: true, openLineage: false }])
  })

  it('clears cross-page selections without changing the clarification text', async () => {
    const wrapper = mount(StandardDataConfirmationEditor, {
      props: { selectedCount: 3, overNote: '排除测试患者', underNote: '' },
    })
    await wrapper.get('button.clear-selection').trigger('click')
    expect(wrapper.emitted('clearSelection')).toHaveLength(1)
    expect(wrapper.get('textarea').element.value).toBe('排除测试患者')
  })
})
