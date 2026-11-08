import { flushPromises, mount, RouterLinkStub } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuthUser } from '../src/api/auth'
import type { OperationsReport, PendingRatio, ReportContext } from '../src/api/report'
import { getOperationsReport } from '../src/api/report'
import { useAuthStore } from '../src/stores/auth'
import { ApiError } from '../src/types/api'
import OperationsView from '../src/views/OperationsView.vue'

vi.mock('../src/api/report', () => ({
  getOperationsReport: vi.fn(),
  getPerformanceReport: vi.fn(),
  getCampaignAnalytics: vi.fn(),
}))

const context: ReportContext = {
  from: '2026-10-01', to: '2026-10-31', fromUtc: '2026-09-30T16:00:00Z', toUtcExclusive: '2026-10-31T16:00:00Z',
  timeZone: 'Asia/Shanghai', sourceSystem: 'ALL', receiverType: 'ALL', metricVersion: 'P2_FACT_V1', sourceWatermark: '2026-10-26T08:00:00Z',
}
const pendingRatio: PendingRatio = { value: null, status: 'PENDING_DEFINITION', questionCode: 'Q13', description: '统计分母待确认' }
const money = {
  paidFen: 123456, platformPaidFen: 123456, institutionPaidFen: 0,
  refundFen: 0, platformRefundFen: 0, institutionRefundFen: 0,
  netPaidFen: 123456, platformNetPaidFen: 123456, institutionNetPaidFen: 0,
}

function report(): OperationsReport {
  return {
    ...context,
    metrics: {
      ...money,
      contactCustomerCount: 12,
      contactEventCount: 20,
      paidOrderCount: 3,
      confirmedAppointmentCount: 8,
      serviceCompletedCount: 6,
      checkedInCustomerCount: 2,
      checkedInAppointmentCount: 4,
      redemptionBatchCount: 1,
      redeemedCustomerCount: 1,
      redeemedCount: 5,
      refundCount: 0,
    },
    staffRows: [{
      staffUserId: 7,
      staffName: '测试管家',
      contactCustomerCount: 12,
      contactEventCount: 20,
      paidOrderCount: 3,
      confirmedAppointmentCount: 8,
      serviceCompletedCount: 6,
      checkedInCustomerCount: 2,
      checkedInAppointmentCount: 4,
      redemptionBatchCount: 1,
      redeemedCustomerCount: 1,
      redeemedCount: 5,
      paidFen: 123456,
      conversionRates: { contactToPaid: pendingRatio, paidToCheckedIn: pendingRatio, checkedInToRedeemed: pendingRatio },
    }],
    institutionRows: [{
      institutionId: 91,
      institutionName: '测试机构',
      confirmedAppointmentCount: 8,
      serviceCompletedCount: 6,
      checkedInCustomerCount: 2,
      checkedInAppointmentCount: 4,
      redemptionBatchCount: 1,
      redeemedCustomerCount: 1,
      redeemedCount: 5,
      pendingReconciliationFen: 0,
      pendingReconciliationStatus: 'PENDING_VERIFICATION',
      conversionRates: { contactToPaid: pendingRatio, paidToCheckedIn: pendingRatio, checkedInToRedeemed: pendingRatio },
    }],
    conversionRates: { contactToPaid: pendingRatio, paidToCheckedIn: pendingRatio, checkedInToRedeemed: pendingRatio },
    drillDown: [{
      factType: 'PAYMENT', sourceId: 501, orderId: 901, customerId: 77, campaignId: null, campaignBatchId: null, skuId: null,
      eventAt: '2026-10-20T02:00:00Z', amountFen: 123456, quantity: null, sourceSystem: 'MANUAL', receiverType: 'PLATFORM',
    }],
    drillDownType: 'PAYMENT',
    drillDownLimit: 50,
  }
}

function emptyReport(): OperationsReport {
  const current = report()
  return {
    ...current,
    metrics: {
      ...current.metrics,
      contactCustomerCount: 0, contactEventCount: 0, paidOrderCount: 0, confirmedAppointmentCount: 0,
      serviceCompletedCount: 0, checkedInCustomerCount: 0, checkedInAppointmentCount: 0,
      redemptionBatchCount: 0, redeemedCustomerCount: 0, redeemedCount: 0, refundCount: 0,
      paidFen: 0, platformPaidFen: 0, netPaidFen: 0, platformNetPaidFen: 0,
    },
    staffRows: [],
    institutionRows: [],
    drillDown: [],
  }
}

function mountView() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore(pinia)
  auth.user = {
    userId: 1, username: 'operations-test', displayName: '报表测试账号', userType: 'EMPLOYEE',
    roles: ['ADMIN'], permissions: ['report:read', 'campaign:read', 'export:read'],
  } satisfies AuthUser
  auth.initialized = true
  return mount(OperationsView, {
    global: { plugins: [pinia, ElementPlus], stubs: { RouterLink: RouterLinkStub } },
    attachTo: document.body,
  })
}

afterEach(() => { document.body.innerHTML = '' })
beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(getOperationsReport).mockResolvedValue(report())
})

describe('OperationsView 真实运营报表', () => {
  it('按原型展示服务完成、管家和机构分组，Q13 与待核实金额不伪造', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(getOperationsReport).toHaveBeenCalledWith(expect.objectContaining({ timeZone: 'Asia/Shanghai', drillDownType: 'PAYMENT' }))
    expect(wrapper.findAll('.proto-stat-card .number').map((node) => node.text())).toEqual(['12 人', '3 单', '6 / 8', '2 人'])
    expect(wrapper.text()).toContain('服务完成 / 确认预约')
    expect(wrapper.text()).not.toContain('到院 / 确认预约')
    const staff = wrapper.get('[data-testid="operations-staff-rows"]')
    expect(staff.text()).toContain('测试管家')
    expect(staff.text()).toContain('服务完成')
    expect(staff.text()).toContain('¥1234.56')
    expect(staff.text()).toContain('待确认')
    expect(staff.text()).not.toContain('0.00%')
    const institutions = wrapper.get('[data-testid="operations-institution-rows"]')
    expect(institutions.text()).toContain('测试机构')
    expect(institutions.text()).toContain('完成服务')
    expect(institutions.text()).toContain('核销次数')
    expect(wrapper.get('[data-testid="institution-reconciliation"]').text()).toBe('待核实')
    expect(wrapper.text()).toContain('已核销客户')
    expect(wrapper.text()).toMatch(/2026-10-26 \d{2}:\d{2}:\d{2}/)
    expect(wrapper.get('[data-testid="operations-facts"]').text()).toContain('订单 #901')
    wrapper.unmount()
  })

  it('已核准待付款金额直接显示真实未付余额', async () => {
    const current = report()
    current.institutionRows[0]!.pendingReconciliationFen = 1000
    current.institutionRows[0]!.pendingReconciliationStatus = 'VERIFIED'
    vi.mocked(getOperationsReport).mockResolvedValue(current)
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.get('[data-testid="institution-reconciliation"]').text()).toBe('¥10.00')
    wrapper.unmount()
  })

  it('加载、空态、失败与重试均保留真实分组状态', async () => {
    let resolveRequest: ((value: OperationsReport) => void) | undefined
    vi.mocked(getOperationsReport).mockReturnValueOnce(new Promise((resolve) => { resolveRequest = resolve }))
    const loadingWrapper = mountView()
    await loadingWrapper.vm.$nextTick()
    expect(loadingWrapper.text()).toContain('正在加载报表')
    resolveRequest?.(emptyReport())
    await flushPromises()
    expect(loadingWrapper.text()).toContain('暂无管家转化数据')
    expect(loadingWrapper.text()).toContain('暂无机构履约数据')
    expect(loadingWrapper.text()).toContain('当前筛选没有对应事实记录')
    expect(loadingWrapper.findAll('.proto-stat-card .number').map((node) => node.text())).toEqual(['0 人', '0 单', '0 / 0', '0 人'])
    loadingWrapper.unmount()

    vi.mocked(getOperationsReport).mockRejectedValueOnce(new ApiError({ status: 403, code: 'FORBIDDEN', message: '无权查看该数据范围' }))
    vi.mocked(getOperationsReport).mockResolvedValueOnce(emptyReport())
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.text()).toContain('无权查看该数据范围')
    expect(wrapper.text()).not.toContain('暂无管家转化数据')
    await wrapper.get('[data-testid="operations-retry"]').trigger('click')
    await flushPromises()
    expect(getOperationsReport).toHaveBeenCalledTimes(3)
    expect(wrapper.text()).not.toContain('无权查看该数据范围')
    expect(wrapper.text()).toContain('暂无管家转化数据')
    wrapper.unmount()
  })
})
