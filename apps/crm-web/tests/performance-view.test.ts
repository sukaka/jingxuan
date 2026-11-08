import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { PerformanceReport, PerformanceRole, ReportContext } from '../src/api/report'
import { getPerformanceReport } from '../src/api/report'
import { ApiError } from '../src/types/api'
import PerformanceView from '../src/views/PerformanceView.vue'
import { reportRangeForPeriod } from '../src/views/reporting/reportUi'

vi.mock('../src/api/report', () => ({
  getOperationsReport: vi.fn(),
  getPerformanceReport: vi.fn(),
  getCampaignAnalytics: vi.fn(),
}))

const context: ReportContext = {
  from: '2026-10-01', to: '2026-10-31', fromUtc: '2026-09-30T16:00:00Z', toUtcExclusive: '2026-10-31T16:00:00Z',
  timeZone: 'Asia/Shanghai', sourceSystem: 'MANUAL', receiverType: 'ALL', metricVersion: 'P2_FACT_V1', sourceWatermark: '2026-10-26T08:00:00Z',
}
const money = {
  paidFen: 234500, platformPaidFen: 234500, institutionPaidFen: 0,
  refundFen: 1200, platformRefundFen: 1200, institutionRefundFen: 0,
  netPaidFen: 233300, platformNetPaidFen: 233300, institutionNetPaidFen: 0,
}

function report(role: PerformanceRole = 'MANAGER'): PerformanceReport {
  return {
    ...context,
    performanceRole: role,
    totals: { ...money, paidOrderCount: 4, refundCount: 1 },
    items: [{
      ...money,
      userId: role === 'MANAGER' ? 7 : 8,
      displayName: role === 'MANAGER' ? '测试管家' : '测试商务',
      attributionStatus: 'AT_SALE_SNAPSHOT',
      paidOrderCount: 4,
      refundCount: 1,
      targetFen: null,
      targetStatus: 'UNCONFIGURED',
      targetCompletionRate: null,
      targetCompletionStatus: 'PENDING_DEFINITION',
      commissionFen: null,
      commissionStatus: 'UNCONFIGURED',
      sourceWatermark: '2026-10-26T08:00:00Z',
    }],
    total: 1,
    page: 1,
    size: 20,
    targetStatus: 'UNCONFIGURED',
    commissionStatus: 'UNCONFIGURED',
    usageStatus: 'NOT_PAYROLL',
    teamTargetFen: null,
    teamTargetStatus: 'UNCONFIGURED',
    teamTargetCompletionRate: null,
    teamTargetCompletionStatus: 'PENDING_DEFINITION',
    dailyItems: [
      { statDate: '2026-10-01', paidOrderCount: 3, refundCount: 1, paidFen: 154500, refundFen: 1200, netPaidFen: 153300 },
      { statDate: '2026-10-02', paidOrderCount: 1, refundCount: 0, paidFen: 80000, refundFen: 0, netPaidFen: 80000 },
    ],
    drillDown: [{
      factType: 'PAYMENT', sourceId: 502, orderId: 902, customerId: 78, campaignId: null, campaignBatchId: null, skuId: null,
      eventAt: '2026-10-02T03:00:00Z', amountFen: 80000, quantity: null, sourceSystem: 'MANUAL', receiverType: 'PLATFORM',
    }],
    drillDownType: 'PAYMENT',
    drillDownLimit: 50,
  }
}

function mountView() {
  return mount(PerformanceView, { global: { plugins: [ElementPlus] }, attachTo: document.body })
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(getPerformanceReport).mockImplementation((query) => Promise.resolve(report(query.performanceRole)))
})
afterEach(() => { document.body.innerHTML = '' })

describe('PerformanceView 管家与商务业绩', () => {
  it('缺失团队目标不补零，真实 dailyItems 绘制柱形和表格，员工下钻使用真实事实', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(getPerformanceReport).toHaveBeenCalledWith(expect.objectContaining({ performanceRole: 'MANAGER', timeZone: 'Asia/Shanghai' }))
    expect(wrapper.findAll('.proto-stat-card .number').map((node) => node.text())).toEqual(['待配置', '¥2333.00', '待确认'])
    expect(wrapper.text()).toContain('不直接作为工资或提成结算依据')
    expect(wrapper.text()).not.toContain('UNCONFIGURED')
    expect(wrapper.text()).not.toContain('NOT PAYROLL')
    expect(wrapper.find('.report-timezone').exists()).toBe(false)
    expect(wrapper.get('[data-testid="performance-fact-drilldown"]').text()).toBe('查看明细')
    expect(wrapper.findAll('[data-testid="daily-bar"]')).toHaveLength(2)
    expect(wrapper.get('[data-testid="performance-daily-table"]').text()).toContain('2026-10-01')
    expect(wrapper.get('[data-testid="performance-daily-table"]').text()).toContain('¥1545.00')
    expect(wrapper.get('[data-testid="performance-daily-table"]').text()).toContain('¥1533.00')

    await wrapper.get('[data-testid="performance-fact-drilldown"]').trigger('click')
    await flushPromises()
    expect(getPerformanceReport).toHaveBeenLastCalledWith(expect.objectContaining({ performanceRole: 'MANAGER', staffUserId: 7, drillDownType: 'PAYMENT' }))
    expect(document.body.textContent).toContain('#902')
    expect(document.body.textContent).toContain('¥800.00')
    wrapper.unmount()
  })

  it('团队目标和完成率直接读取真实汇总字段，不依赖浏览器汇总分页行', async () => {
    vi.mocked(getPerformanceReport).mockResolvedValue({
      ...report(),
      total: 99,
      teamTargetFen: 500000,
      teamTargetStatus: 'APPROVED',
      teamTargetCompletionRate: 46.66,
      teamTargetCompletionStatus: 'CALCULATED',
    })
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.findAll('.proto-stat-card .number').map((node) => node.text())).toEqual(['¥5000.00', '¥2333.00', '46.66%'])
    wrapper.unmount()
  })

  it('日周月快捷范围按显式时区计算真实日期边界', () => {
    const instant = new Date('2026-10-26T16:30:00Z')
    expect(reportRangeForPeriod('day', 'Asia/Shanghai', instant)).toEqual(['2026-10-27', '2026-10-27'])
    expect(reportRangeForPeriod('week', 'Asia/Shanghai', instant)).toEqual(['2026-10-26', '2026-10-27'])
    expect(reportRangeForPeriod('month', 'Asia/Shanghai', instant)).toEqual(['2026-10-01', '2026-10-27'])
  })

  it('快捷筛选按钮只改变查询范围并重新请求，不在前端聚合日数据', async () => {
    const wrapper = mountView()
    await flushPromises()
    for (const period of ['day', 'week', 'month'] as const) {
      await wrapper.get(`[data-testid="performance-period-${period}"]`).trigger('click')
      await flushPromises()
      const expected = reportRangeForPeriod(period, 'Asia/Shanghai')
      expect(getPerformanceReport).toHaveBeenLastCalledWith(expect.objectContaining({ from: expected[0], to: expected[1], timeZone: 'Asia/Shanghai' }))
    }
    wrapper.unmount()
  })

  it('dailyItems 为空或没有可绘制金额时显示空态，不补日期或零柱', async () => {
    vi.mocked(getPerformanceReport).mockResolvedValue({ ...report(), dailyItems: [] })
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.text()).toContain('当前范围没有日事实记录，不生成趋势或补零')
    expect(wrapper.findAll('[data-testid="daily-bar"]')).toHaveLength(0)
    wrapper.unmount()
  })

  it('切换商务页签重新请求对应岗位，错误时不保留上一岗位数据', async () => {
    const wrapper = mountView()
    await flushPromises()
    const businessTab = wrapper.findAll('.el-tabs__item').find((node) => node.text() === '商务业绩')
    await businessTab?.trigger('click')
    await flushPromises()
    expect(getPerformanceReport).toHaveBeenLastCalledWith(expect.objectContaining({ performanceRole: 'BUSINESS' }))
    expect(wrapper.text()).toContain('测试商务')

    vi.mocked(getPerformanceReport).mockRejectedValueOnce(new ApiError({ status: 0, code: 'NETWORK_ERROR', message: '报表服务暂不可用' }))
    const managerTab = wrapper.findAll('.el-tabs__item').find((node) => node.text() === '管家业绩')
    await managerTab?.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('报表服务暂不可用')
    expect(wrapper.text()).not.toContain('测试商务')
    expect(wrapper.find('[data-testid="performance-retry"]').exists()).toBe(true)
    wrapper.unmount()
  })
})
