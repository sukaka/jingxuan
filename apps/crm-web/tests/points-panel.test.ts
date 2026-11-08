import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessage } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuthUser } from '../src/api/auth'
import type { CustomerPoints } from '../src/api/customer'
import { adjustCustomerPoints, getCustomerPoints } from '../src/api/customer'
import { useAuthStore } from '../src/stores/auth'
import { ApiError } from '../src/types/api'
import PointsPanel from '../src/views/customer/PointsPanel.vue'

vi.mock('../src/api/customer', () => ({
  adjustCustomerPoints: vi.fn(),
  getCustomerPoints: vi.fn(),
}))

const initialPoints: CustomerPoints = {
  balance: 320,
  ledger: [{
    id: 71,
    delta: 320,
    reasonCode: 'MANUAL_ADJUST',
    sourceType: 'MANUAL_ADJUST',
    sourceRef: 'TEST-LEDGER-71',
    operatorUserId: 8,
    createdAt: '2026-10-01T00:00:00Z',
  }],
}

const user: AuthUser = {
  userId: 8,
  username: 'test-lead',
  displayName: '测试主管',
  userType: 'EMPLOYEE',
  roles: ['MANAGER_LEAD'],
  permissions: ['customer:read', 'customer:points_adjust'],
}

const wrappers: VueWrapper[] = []

function mountPanel() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = user
  auth.initialized = true
  const wrapper = mount(PointsPanel, {
    props: { customerId: 42, birthday: '1992-04-18' },
    global: {
      plugins: [ElementPlus, pinia],
      stubs: {
        'el-dialog': { template: '<div class="dialog-stub"><slot /><slot name="footer" /></div>' },
      },
    },
    attachTo: document.body,
  })
  wrappers.push(wrapper)
  return wrapper
}

async function setModel(wrapper: VueWrapper, testId: string, value: unknown) {
  if (testId === 'points-reason') {
    await wrapper.get(`[data-testid="${testId}"]`).setValue(String(value))
  } else {
    await wrapper.get(`[data-testid="${testId}"]`).find('input').setValue(String(value))
  }
  await flushPromises()
}

async function openAdjustment(wrapper: VueWrapper, delta = -50) {
  await wrapper.get('[data-testid="points-open-adjust"]').trigger('click')
  await setModel(wrapper, 'points-delta', delta)
  await setModel(wrapper, 'points-reason', '测试人工冲正')
}

beforeEach(() => {
  vi.mocked(getCustomerPoints).mockResolvedValue(initialPoints)
})

afterEach(() => {
  while (wrappers.length) wrappers.pop()?.unmount()
  document.body.innerHTML = ''
  vi.clearAllMocks()
  vi.restoreAllMocks()
})

describe('PointsPanel 生日与积分账本', () => {
  it('读取档案生日和真实余额，不自动按生日或消费赠送积分', async () => {
    const wrapper = mountPanel()
    await flushPromises()

    expect(getCustomerPoints).toHaveBeenCalledWith(42)
    expect(wrapper.get('[data-testid="points-balance"]').text()).toBe('320')
    expect(wrapper.get('[data-testid="customer-birthday"]').text()).toBe('1992-04-18')
    expect(wrapper.text()).toContain('不会因生日或消费自动赠送')
    expect(adjustCustomerPoints).not.toHaveBeenCalled()
  })

  it('人工调整只追加流水，失败重试沿用同一幂等键并采用接口返回余额', async () => {
    vi.spyOn(ElMessage, 'error').mockImplementation(() => ({ close: vi.fn() }))
    vi.mocked(adjustCustomerPoints)
      .mockRejectedValueOnce(new ApiError({ status: 0, code: 'NETWORK_ERROR', message: '网络超时测试' }))
      .mockResolvedValueOnce({ ...initialPoints, balance: 270 })
    const wrapper = mountPanel()
    await flushPromises()
    await openAdjustment(wrapper)

    await wrapper.get('[data-testid="points-submit-adjust"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="points-submit-adjust"]').trigger('click')
    await flushPromises()

    expect(adjustCustomerPoints).toHaveBeenCalledTimes(2)
    const first = vi.mocked(adjustCustomerPoints).mock.calls[0]
    const second = vi.mocked(adjustCustomerPoints).mock.calls[1]
    expect(first?.[0]).toBe(42)
    expect(first?.[1]).toEqual({ delta: -50, reasonCode: 'MANUAL_ADJUST', reason: '测试人工冲正' })
    expect(first?.[2]).toEqual(expect.any(String))
    expect(first?.[2]).not.toBe('')
    expect(second?.[2]).toBe(first?.[2])
    expect(wrapper.get('[data-testid="points-balance"]').text()).toBe('270')
    expect(getCustomerPoints).toHaveBeenCalledTimes(1)
  })

  it('负余额被后端拒绝时明确提示，且不伪装流水追加成功', async () => {
    const error = vi.spyOn(ElMessage, 'error').mockImplementation(() => ({ close: vi.fn() }))
    const success = vi.spyOn(ElMessage, 'success').mockImplementation(() => ({ close: vi.fn() }))
    vi.mocked(adjustCustomerPoints).mockRejectedValue(
      new ApiError({ status: 409, code: 'CONFLICT', message: '积分余额不足，调整后不得为负' }),
    )
    const wrapper = mountPanel()
    await flushPromises()
    await openAdjustment(wrapper, -999)

    await wrapper.get('[data-testid="points-submit-adjust"]').trigger('click')
    await flushPromises()

    expect(error).toHaveBeenCalledWith(expect.stringContaining('积分余额不足'))
    expect(success).not.toHaveBeenCalled()
    expect(wrapper.get('[data-testid="points-balance"]').text()).toBe('320')
  })
})
