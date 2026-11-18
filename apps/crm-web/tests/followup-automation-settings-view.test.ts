import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getFollowupAutomationPolicy, updateFollowupAutomationPolicy } from '../src/api/followup-automation'
import FollowupAutomationSettingsView from '../src/views/FollowupAutomationSettingsView.vue'

vi.mock('../src/api/followup-automation', () => ({
  getFollowupAutomationPolicy: vi.fn(),
  updateFollowupAutomationPolicy: vi.fn(),
}))

const policy = { defaultIntervalDays: 14, intentOverrides: [], version: 2 }

beforeEach(() => {
  vi.mocked(getFollowupAutomationPolicy).mockReset().mockResolvedValue(policy)
  vi.mocked(updateFollowupAutomationPolicy).mockReset()
})

afterEach(() => { document.body.innerHTML = '' })

describe('FollowupAutomationSettingsView 跟进策略', () => {
  it('加载默认间隔并保存意向覆盖与当前版本', async () => {
    vi.mocked(updateFollowupAutomationPolicy).mockResolvedValue({
      defaultIntervalDays: 14,
      intentOverrides: [{ intentLevel: 'HIGH', intervalDays: 7 }],
      version: 3,
    })
    const wrapper = mount(FollowupAutomationSettingsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(wrapper.text()).toContain('14')

    await wrapper.get('[data-testid="add-override"]').trigger('click')
    await wrapper.get('[data-testid="save-policy"]').trigger('click')
    await flushPromises()

    expect(updateFollowupAutomationPolicy).toHaveBeenCalledWith({
      defaultIntervalDays: 14,
      intentOverrides: [{ intentLevel: 'HIGH', intervalDays: 7 }],
      version: 2,
    }, expect.any(String))
    expect(wrapper.text()).toContain('策略已保存')
  })

  it('保存失败后用同一幂等键重试且不重复提交进行中请求', async () => {
    vi.mocked(updateFollowupAutomationPolicy)
      .mockRejectedValueOnce(new Error('网络中断'))
      .mockResolvedValueOnce({ ...policy, version: 3 })
    const wrapper = mount(FollowupAutomationSettingsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.get('[data-testid="save-policy"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('网络中断')
    await wrapper.get('[data-testid="save-policy"]').trigger('click')
    await flushPromises()

    expect(updateFollowupAutomationPolicy).toHaveBeenCalledTimes(2)
    const firstKey = vi.mocked(updateFollowupAutomationPolicy).mock.calls[0]?.[1]
    const secondKey = vi.mocked(updateFollowupAutomationPolicy).mock.calls[1]?.[1]
    expect(firstKey).toBe(secondKey)
  })
})
