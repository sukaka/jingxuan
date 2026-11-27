import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import type { AxiosAdapter } from 'axios'
import ElementPlus, { ElMessage } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuthUser } from '../src/api/auth'
import { syncYouzanAnnualSnapshots } from '../src/api/annualReport'
import type { CustomerYouzanHistoryOrders } from '../src/api/customer'
import { getCustomerYouzanHistoryOrders } from '../src/api/customer'
import { http } from '../src/api/http'
import { listOrders } from '../src/api/trading'
import { useAuthStore } from '../src/stores/auth'
import CustomerBusinessPanel from '../src/views/customer/CustomerBusinessPanel.vue'

vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<typeof import('element-plus')>()
  return {
    ...actual,
    ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() },
  }
})

vi.mock('../src/api/trading', () => ({
  listOrders: vi.fn(),
  listEntitlements: vi.fn().mockResolvedValue({ items: [], total: 0, page: 1, size: 100 }),
}))

vi.mock('../src/api/fulfillment', () => ({
  listRedemptions: vi.fn().mockResolvedValue({ items: [], total: 0, page: 1, size: 100 }),
}))

vi.mock('../src/api/customer', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../src/api/customer')>()),
  getCustomerYouzanHistoryOrders: vi.fn(),
}))

vi.mock('../src/api/annualReport', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../src/api/annualReport')>()),
  syncYouzanAnnualSnapshots: vi.fn(),
}))

vi.mock('../src/views/references', () => ({
  useReferenceNames: () => ({ institutionLabel: (id: number) => `机构#${id}` }),
}))

const sampleHistory: CustomerYouzanHistoryOrders = {
  customerId: 88,
  hasYouzanIdentity: true,
  linkedShopIds: ['40880001'],
  summary: {
    totalOrderCount: 3,
    paidOrderCount: 2,
    totalPaymentFen: 438000,
    totalRefundedFen: 40000,
    netSpendFen: 398000,
    firstOrderAt: '2025-05-01T02:05:00Z',
    lastOrderAt: '2026-08-15T04:02:00Z',
    lastSyncedAt: '2026-11-27T01:00:00Z',
  },
  orders: {
    items: [
      {
        id: 102,
        shopId: '40880001',
        tid: 'E202608150002',
        orderStatus: 'TRADE_SUCCESS',
        paid: true,
        orderedAt: '2026-08-15T04:00:00Z',
        paidAt: '2026-08-15T04:02:00Z',
        paymentFen: 240000,
        refundedFen: 40000,
        netPaymentFen: 200000,
        titleSummary: '超光子嫩肤全年卡',
        items: [
          {
            itemId: '1002',
            skuId: '2002',
            title: '超光子嫩肤全年卡',
            skuProps: '全模式',
            num: 2,
            priceFen: 120000,
          },
        ],
        syncedAt: '2026-11-27T01:00:00Z',
      },
    ],
    total: 2,
    page: 1,
    size: 10,
  },
}

const wrappers: VueWrapper[] = []
const originalAdapter = http.defaults.adapter

function mountPanel(permissions: string[], userType = 'EMPLOYEE') {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = {
    userId: 1,
    username: 'tester',
    displayName: '测试员工',
    userType,
    roles: userType === 'INSTITUTION' ? ['INSTITUTION_STAFF'] : ['ADMIN'],
    permissions,
  } as AuthUser
  auth.initialized = true

  const wrapper = mount(CustomerBusinessPanel, {
    props: { customerId: 88, type: 'orders' },
    global: { plugins: [ElementPlus, pinia] },
    attachTo: document.body,
  })
  wrappers.push(wrapper)
  return wrapper
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(listOrders).mockResolvedValue({ items: [], total: 0, page: 1, size: 100 })
  vi.mocked(getCustomerYouzanHistoryOrders).mockResolvedValue(sampleHistory)
  vi.mocked(syncYouzanAnnualSnapshots).mockResolvedValue({
    shopId: '40880001',
    startDate: '2025-01-01',
    endDate: '2025-12-31',
    windowsPulled: 5,
    ordersSynced: 12,
    paidOrdersSynced: 10,
    buyersSynced: 4,
    buyersEnriched: 0,
    openIdBoundCount: 0,
    unionIdBoundCount: 0,
    linkedCustomerCount: 2,
    warnings: [],
    syncedAt: '2026-11-27T02:00:00Z',
  })
})

afterEach(() => {
  http.defaults.adapter = originalAdapter
  while (wrappers.length) wrappers.pop()?.unmount()
  document.body.innerHTML = ''
})

describe('CustomerBusinessPanel 客户详情有赞历史订单（只读快照）', () => {
  it('展示有赞只读历史消费汇总、商品明细、状态切换与管理员一键同步历史快照', async () => {
    const wrapper = mountPanel(['customer:read', 'integration:youzan:manage'])
    await flushPromises()

    expect(getCustomerYouzanHistoryOrders).toHaveBeenCalledWith(88, { paidOnly: true, page: 1, size: 10 })
    const summaryText = wrapper.get('[data-testid="yz-history-summary"]').text()
    expect(summaryText).toContain('2 笔')
    expect(summaryText).toContain('¥4380.00')
    expect(summaryText).toContain('¥400.00')
    expect(summaryText).toContain('¥3980.00')

    const tableText = wrapper.get('[data-testid="customer-youzan-history-table"]').text()
    expect(tableText).toContain('E202608150002')
    expect(tableText).toContain('超光子嫩肤全年卡')
    expect(tableText).toContain('全模式')
    expect(tableText).toContain('交易完成')

    // 切换为查看全部状态（含未支付/已关闭）
    const paidSwitch = wrapper.getComponent('[data-testid="yz-history-paid-only"]') as VueWrapper
    paidSwitch.vm.$emit('update:modelValue', false)
    paidSwitch.vm.$emit('change', false)
    await flushPromises()
    expect(getCustomerYouzanHistoryOrders).toHaveBeenLastCalledWith(88, { paidOnly: false, page: 1, size: 10 })

    // 管理员点击「同步历史快照」并选择 2025 年拉取，成功后自动刷新客户历史单
    await wrapper.get('[data-testid="yz-history-sync-btn"]').trigger('click')
    await flushPromises()
    const preset2025 = document.body.querySelector('[data-testid="yz-snap-preset-2025"]') as HTMLButtonElement | null
    expect(preset2025).not.toBeNull()
    preset2025!.click()
    await flushPromises()
    const submitBtn = document.body.querySelector('[data-testid="yz-snap-submit"]') as HTMLButtonElement | null
    expect(submitBtn).not.toBeNull()
    submitBtn!.click()
    await flushPromises()

    expect(syncYouzanAnnualSnapshots).toHaveBeenCalledWith(
      expect.objectContaining({
        shopId: '40880001',
        startDate: '2025-01-01',
        endDate: '2025-12-31',
        enrichBuyerProfile: false,
      }),
      expect.stringMatching(/^youzan\.history\.snapshot:/),
    )
    expect(ElMessage.success).toHaveBeenCalledWith(expect.stringContaining('共同步 12 笔'))
    expect(getCustomerYouzanHistoryOrders).toHaveBeenCalledTimes(3)
  })

  it('普通管家可只读查看但不展示同步按钮；机构账号不展示且不请求有赞历史订单', async () => {
    const managerWrapper = mountPanel(['customer:read'])
    await flushPromises()
    expect(managerWrapper.find('[data-testid="customer-youzan-history-section"]').exists()).toBe(true)
    expect(managerWrapper.find('[data-testid="yz-history-sync-btn"]').exists()).toBe(false)

    vi.clearAllMocks()
    const instWrapper = mountPanel(['customer:read'], 'INSTITUTION')
    await flushPromises()
    expect(instWrapper.find('[data-testid="customer-youzan-history-section"]').exists()).toBe(false)
    expect(getCustomerYouzanHistoryOrders).not.toHaveBeenCalled()
  })

  it('有赞历史加载失败时仅局部告警，不影响 CRM 正式订单区块展示', async () => {
    vi.mocked(getCustomerYouzanHistoryOrders).mockRejectedValueOnce(new Error('历史快照接口暂不可用'))
    const wrapper = mountPanel(['customer:read'])
    await flushPromises()

    expect(wrapper.find('[data-testid="crm-formal-orders-section"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="customer-youzan-history-section"]').text()).toContain('历史快照接口暂不可用')
  })

  it('API 契约：getCustomerYouzanHistoryOrders 请求正确路径与查询参数', async () => {
    const actualCustomerApi = await vi.importActual<typeof import('../src/api/customer')>('../src/api/customer')
    const calls: Array<{ method?: string; url?: string; params?: unknown }> = []
    const adapter: AxiosAdapter = async (config) => {
      calls.push({ method: config.method, url: config.url, params: config.params })
      return {
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
        data: { code: 'OK', message: 'success', data: sampleHistory, requestId: 'req-1', timestamp: '' },
      }
    }
    http.defaults.adapter = adapter

    const data = await actualCustomerApi.getCustomerYouzanHistoryOrders(88, { paidOnly: false, page: 2, size: 20 })
    expect(data.customerId).toBe(88)
    expect(calls[0]).toEqual({
      method: 'get',
      url: '/customers/88/youzan-history-orders',
      params: { paidOnly: false, page: 2, size: 20 },
    })
  })
})
