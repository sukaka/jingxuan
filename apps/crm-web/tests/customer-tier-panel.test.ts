import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessage } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { CustomerTierView } from '../src/api/customerTier'
import {
  adjustCustomerTier,
  getCustomerTier,
  listCustomerTierHistory,
  recalculateCustomerTier,
} from '../src/api/customerTier'
import { useAuthStore } from '../src/stores/auth'
import CustomerTierPanel from '../src/views/customer/CustomerTierPanel.vue'

vi.mock('../src/api/customerTier', () => ({
  adjustCustomerTier: vi.fn(),
  getCustomerTier: vi.fn(),
  listCustomerTierHistory: vi.fn(),
  recalculateCustomerTier: vi.fn(),
}))

const view: CustomerTierView = {
  customerId: 7,
  tierCode: 'A',
  locked: true,
  lockReason: '人工复核',
  customerVersion: 7,
  activeRuleId: 3,
  latestSnapshot: {
    snapshotId: 10,
    customerId: 7,
    ruleId: 3,
    asOf: '2026-01-01T00:00:00Z',
    windowStartInclusive: '2025-01-01T00:00:00Z',
    windowEndExclusive: '2026-01-01T00:00:00Z',
    timezone: 'Asia/Shanghai',
    paymentAmountFen: 10000,
    paidAmountFen: 1000000,
    refundAmountFen: 200000,
    actualConsumptionFen: 800000,
    orderCount: 2,
    manualFollowupCount: 0,
    archivedMessageCount: 0,
    communicationCount: null,
    communicationComplete: false,
    score: null,
    suggestedTierCode: 'B',
    sourceTrigger: 'MANUAL',
  },
  pendingReview: null,
}

async function mountPanel() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = {
    userId: 1, username: 'admin', displayName: '管理员', userType: 'EMPLOYEE',
    roles: ['ADMIN'], permissions: ['customer:read', 'customer:write'],
  }
  const wrapper = mount(CustomerTierPanel, {
    props: { customerId: 7 },
    global: {
      plugins: [ElementPlus, pinia],
      stubs: {
        teleport: true,
        ElDialog: {
          props: { modelValue: Boolean },
          template: '<div v-if="modelValue"><slot /><slot name="footer" /></div>',
        },
        ElSelect: { template: '<select><slot /></select>' },
        ElOption: { props: ['label', 'value'], template: '<option :value="value">{{ label }}</option>' },
      },
    },
    attachTo: document.body,
  })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(getCustomerTier).mockResolvedValue(view)
  vi.mocked(listCustomerTierHistory).mockResolvedValue([])
  vi.mocked(recalculateCustomerTier).mockResolvedValue(view.latestSnapshot!)
  vi.mocked(adjustCustomerTier).mockResolvedValue(view)
  vi.spyOn(ElMessage, 'success').mockImplementation(() => ({ close: vi.fn() }))
})

afterEach(() => {
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})

describe('客户分级面板', () => {
  it('展示实际消费金额、独立退款与建议等级，不再显示得分或权重', async () => {
    const wrapper = await mountPanel()

    expect(wrapper.get('[data-testid="tier-actual-consumption"]').text()).toBe('¥8000.00')
    expect(wrapper.text()).toContain('¥10000.00')
    expect(wrapper.text()).toContain('¥2000.00')
    expect(wrapper.text()).toContain('建议等级')
    expect(wrapper.text()).toContain('B')
    expect(wrapper.text()).toContain('有效订单实付金额－成功退款金额')
    expect(wrapper.text()).not.toMatch(/加权分|最低得分|权重/)
    await wrapper.get('[data-testid="tier-recalculate"]').trigger('click')
    await flushPromises()
    expect(recalculateCustomerTier).toHaveBeenCalledWith(7, 7, expect.any(String))
  })

  it('人工锁定通过专用调整接口并携带 version', async () => {
    const wrapper = await mountPanel()

    await wrapper.get('[data-testid="tier-adjust"]').trigger('click')
    await wrapper.get('[data-testid="tier-adjust-reason"]').setValue('继续人工锁定')
    await wrapper.get('[data-testid="tier-adjust-submit"]').trigger('click')
    await flushPromises()

    expect(adjustCustomerTier).toHaveBeenCalledWith(7, {
      tierCode: 'A', reason: '继续人工锁定', lock: true, version: 7,
    }, expect.any(String))
  })
})
