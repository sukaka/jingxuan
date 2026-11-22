import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getFollowupAutomationPolicy, updateFollowupAutomationPolicy } from '../src/api/followup-automation'
import { useAuthStore } from '../src/stores/auth'
import FollowupAutomationSettingsView from '../src/views/FollowupAutomationSettingsView.vue'

vi.mock('../src/api/followup-automation', () => ({
  getFollowupAutomationPolicy: vi.fn(),
  updateFollowupAutomationPolicy: vi.fn(),
}))

const policy = { defaultIntervalDays: 14, intentOverrides: [], version: 2 }

function mountView(permissions = ['followup:read', 'followup:policy:manage']) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore(pinia)
  auth.user = {
    userId: 1,
    username: 'ops-lead',
    displayName: '运营主管',
    userType: 'EMPLOYEE',
    roles: ['OPERATION_LEAD'],
    permissions,
  }
  auth.initialized = true
  return mount(FollowupAutomationSettingsView, { global: { plugins: [ElementPlus, pinia] } })
}

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
    const wrapper = mountView()
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
    const wrapper = mountView()
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

  it('仅具备 followup:read 时处于只读态，隐藏保存与编辑动作', async () => {
    const wrapper = mountView(['followup:read'])
    await flushPromises()

    expect(wrapper.find('[data-testid="followup-readonly-alert"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="add-override"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="save-policy"]').exists()).toBe(false)
    expect(updateFollowupAutomationPolicy).not.toHaveBeenCalled()
  })
})
