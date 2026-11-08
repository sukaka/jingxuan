import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuthUser } from '../src/api/auth'
import type { ReconciliationIssue, SettlementBatch, SettlementBatchDetail } from '../src/api/settlement'
import { getSettlementBatch, getSettlementReconciliation, listSettlementBatches } from '../src/api/settlement'
import { useAuthStore } from '../src/stores/auth'
import SettlementsView from '../src/views/SettlementsView.vue'
import { amountStatusLabel, providerLabel, settlementAmount, settlementPeriodLabel, settlementStatusLabel, settlementTypeLabel } from '../src/views/finance/financeUi'

vi.mock('../src/api/settlement', () => ({
  listSettlementBatches: vi.fn(),
  getSettlementBatch: vi.fn(),
  getSettlementReconciliation: vi.fn(),
  createSettlementBatch: vi.fn(),
  verifySettlementBatch: vi.fn(),
  recordSettlementPayment: vi.fn(),
}))

vi.mock('../src/api/fulfillment', () => ({ listRedemptions: vi.fn() }))

function makeUser(userType: string, permissions: string[]): AuthUser {
  return { userId: 10, username: 'settlement-test', displayName: '结算测试', userType, roles: userType === 'INSTITUTION' ? ['INSTITUTION_FINANCE'] : ['ADMIN'], permissions }
}

const batch: SettlementBatch = {
  id: 301,
  institutionId: 91,
  settlementType: 'MONTHLY',
  periodKey: '2026-10',
  status: 'DRAFT',
  amountStatus: 'PENDING_VERIFICATION',
  grossFen: 0,
  commissionFen: 0,
  netPayableFen: 0,
  paidFen: 0,
  ruleVersion: null,
  verificationEvidence: null,
  createdAt: '2026-10-26T01:00:00Z',
  updatedAt: '2026-10-26T01:00:00Z',
  version: 0,
}

const detail: SettlementBatchDetail = {
  batch,
  lines: [{ id: 1, settlementBatchId: 301, redemptionId: 1001, amountStatus: 'PENDING_VERIFICATION', grossFen: 0, commissionFen: 0, payableFen: 0, rateSnapshot: '{"onlineRate":"0.18"}', pendingReason: 'Q02/Q04 待人工核实', createdAt: '2026-10-26T01:00:00Z' }],
  payments: [],
}

const wrappers: VueWrapper[] = []
afterEach(() => {
  while (wrappers.length) wrappers.pop()?.unmount()
  document.body.innerHTML = ''
})

function mountView(user: AuthUser) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = user
  auth.initialized = true
  const wrapper = mount(SettlementsView, { global: { plugins: [ElementPlus, pinia] }, attachTo: document.body })
  wrappers.push(wrapper)
  return wrapper
}

describe('结算中文展示文案', () => {
  it('已知枚举使用中文，未知枚举不原样暴露技术值', () => {
    expect(settlementStatusLabel('DRAFT')).toBe('待核准')
    expect(settlementStatusLabel('UNKNOWN_STATUS')).toBe('未知批次状态')
    expect(amountStatusLabel('UNKNOWN_STATUS')).toBe('未知金额状态')
    expect(settlementTypeLabel('UNKNOWN_TYPE')).toBe('未知结算方式')
    expect(providerLabel('MOCK')).toBe('模拟验签通道')
    expect(providerLabel('UNKNOWN_PROVIDER')).toBe('未知支付渠道')
  })

  it('次卡次结周期显示核销编号而非技术周期键', () => {
    expect(settlementPeriodLabel('MONTHLY', '2026-10')).toBe('2026-10')
    expect(settlementPeriodLabel('PER_REDEMPTION', 'REDEMPTION:1001')).toBe('核销 #1001')
    expect(settlementPeriodLabel('PER_REDEMPTION', 'unexpected')).toBe('单笔正式核销')
  })
})

describe('SettlementsView 待核实金额、权限与对账', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(listSettlementBatches).mockResolvedValue({ items: [batch], total: 1, page: 1, size: 20 })
    vi.mocked(getSettlementBatch).mockResolvedValue(detail)
    vi.mocked(getSettlementReconciliation).mockResolvedValue({
      generatedAt: '2026-10-26T02:00:00Z',
      issueCount: 2,
      issues: [
        { checkType: 'UNBATCHED_REDEMPTION', resourceId: 1002, institutionId: 91, expectedCount: 1, actualCount: 0, expectedFen: null, actualFen: null, detail: '正式核销尚未进入结算批次' },
        { checkType: 'UNKNOWN_CHECK' as unknown as ReconciliationIssue['checkType'], resourceId: 1003, institutionId: 91, expectedCount: 1, actualCount: 0, expectedFen: null, actualFen: null, detail: '未知检查差异' },
      ],
    })
  })

  it('待核实金额绝不格式化成 0 元，未核准不显示付款', async () => {
    expect(settlementAmount('PENDING_VERIFICATION', 0)).toBe('待核实')
    const wrapper = mountView(makeUser('EMPLOYEE', ['settlement:read', 'settlement:write']))
    await flushPromises()

    const amounts = wrapper.findAll('.finance-table-desktop .finance-amount').map((node) => node.text())
    expect(amounts).toEqual(['待核实', '待核实'])
    expect(wrapper.find('[data-testid="settlement-pay"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="settlement-verify"]').exists()).toBe(true)

    await wrapper.get('[data-testid="settlement-detail"]').trigger('click')
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 40))
    expect(document.body.textContent).toContain('金额待核实：当前 0 值不代表应结金额')
    expect(document.body.textContent).toContain('Q02/Q04 待人工核实')
    expect(document.body.querySelector('[data-testid="settlement-detail-pay"]')).toBeNull()
  })

  it('机构账号只读，即使返回写权限也不展示创建核准付款', async () => {
    const wrapper = mountView(makeUser('INSTITUTION', ['settlement:read', 'settlement:write']))
    await flushPromises()

    expect(wrapper.find('[data-testid="settlement-create"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="settlement-verify"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="settlement-pay"]').exists()).toBe(false)
  })

  it('对账展示后端真实差异与数量，不提供自动修账操作', async () => {
    const wrapper = mountView(makeUser('INSTITUTION', ['settlement:read']))
    await flushPromises()
    const tab = wrapper.findAll('.el-tabs__item').find((item) => item.text() === '风险对账')
    expect(tab).toBeDefined()
    await tab?.trigger('click')
    await flushPromises()

    expect(getSettlementReconciliation).toHaveBeenCalledWith({ institutionId: undefined })
    expect(wrapper.text()).toContain('未入批正式核销')
    expect(wrapper.text()).toContain('正式核销尚未进入结算批次')
    expect(wrapper.text()).toContain('资源 #1002')
    expect(wrapper.text()).toContain('未知检查类型')
    expect(wrapper.text()).not.toContain('UNKNOWN_CHECK')
    expect(wrapper.text()).toContain('只读检查，不自动修账或补流水')
    expect(wrapper.find('button').text()).not.toContain('修账')
  })
})
