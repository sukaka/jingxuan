import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { OperationsDashboard, OperationsMetrics } from '../src/api/report'
import { getOperationsDashboard } from '../src/api/report'
import { ApiError } from '../src/types/api'
import OperationsDashboardView from '../src/views/dashboard/OperationsDashboardView.vue'
import { todayInShanghai } from '../src/views/reporting/reportUi'

vi.mock('../src/api/report', () => ({ getOperationsDashboard: vi.fn() }))
vi.mock('../src/views/dashboard/DashboardChart.vue', () => ({
  default: { name: 'DashboardChart', props: ['option', 'label'], template: '<div class="chart-stub">{{ label }}</div>' },
}))

const context = {
  from: '2026-10-01', to: '2026-10-31', fromUtc: '2026-09-30T16:00:00Z', toUtcExclusive: '2026-10-31T16:00:00Z',
  timeZone: 'Asia/Shanghai', sourceSystem: 'ALL', receiverType: 'ALL', metricVersion: 'P2_FACT_V1', sourceWatermark: '2026-10-20T03:00:00Z',
}
const money = {
  paidFen: 123456, platformPaidFen: 123456, institutionPaidFen: 0,
  refundFen: 2345, platformRefundFen: 2345, institutionRefundFen: 0,
  netPaidFen: 121111, platformNetPaidFen: 121111, institutionNetPaidFen: 0,
}

function dashboard(): OperationsDashboard {
  return {
    ...context,
    metrics: {
      ...money, contactCustomerCount: 0, contactEventCount: 0, paidOrderCount: 3,
      confirmedAppointmentCount: 8, serviceCompletedCount: 6, checkedInCustomerCount: 2,
      checkedInAppointmentCount: 4, redemptionBatchCount: 1, redeemedCustomerCount: 1, redeemedCount: 5, refundCount: 1,
    },
    dailyItems: [
      { statDate: '2026-10-18', paidOrderCount: 3, refundCount: 0, paidFen: 123456, refundFen: 0, netPaidFen: 123456 },
      { statDate: '2026-10-20', paidOrderCount: 0, refundCount: 1, paidFen: 0, refundFen: 2345, netPaidFen: -2345 },
    ],
    institutionRows: [{
      institutionId: 91, institutionName: '测试机构', confirmedAppointmentCount: 8, serviceCompletedCount: 6,
      checkedInCustomerCount: 2, checkedInAppointmentCount: 4, redemptionBatchCount: 1,
      redeemedCustomerCount: 1, redeemedCount: 5, pendingReconciliationFen: null,
      pendingReconciliationStatus: 'PENDING_VERIFICATION', conversionRates: {},
    }],
    campaignRows: [{
      campaignId: 71, campaignBatchId: 81, campaignCode: 'CAMPAIGN', campaignBatchCode: 'BATCH',
      campaignName: '真实活动', campaignBatchName: '真实批次', paidOrderCount: 3, refundCount: 1,
      confirmedAppointmentCount: 8, checkedInCustomerCount: 2, checkedInAppointmentCount: 4,
      redemptionBatchCount: 1, redeemedCount: 5, ...money,
      touchCustomerCount: null, touchStatus: 'SOURCE_NOT_AVAILABLE', conversionRates: {}, sourceWatermark: null,
    }],
    duty: {
      newCustomerCount: 2, pendingTaskCount: 19, dueFollowupCount: 8, overdueTaskCount: 2,
      pendingAppointmentCount: 6, awaitingRedemptionCount: 3, pendingRefundCount: 2,
    },
  }
}

function emptyDashboard(): OperationsDashboard {
  const current = dashboard()
  const zeroMetrics: OperationsMetrics = Object.fromEntries(Object.keys(current.metrics).map((key) => [key, 0])) as unknown as OperationsMetrics
  return {
    ...current, metrics: zeroMetrics, dailyItems: [], institutionRows: [], campaignRows: [], sourceWatermark: null,
    duty: {
      newCustomerCount: 0, pendingTaskCount: 0, dueFollowupCount: 0, overdueTaskCount: 0,
      pendingAppointmentCount: 0, awaitingRedemptionCount: 0, pendingRefundCount: 0,
    },
  }
}

async function mountView() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/operations', component: { template: '<div>运营中心</div>' } },
      { path: '/operations/dashboard', component: OperationsDashboardView },
      { path: '/appointments', component: { template: '<div>预约列表</div>' } },
      { path: '/tasks', component: { template: '<div>任务列表</div>' } },
      { path: '/refunds', component: { template: '<div>退款列表</div>' } },
    ],
  })
  await router.push('/operations/dashboard')
  await router.isReady()
  const wrapper = mount(OperationsDashboardView, {
    global: { plugins: [router, ElementPlus] },
    attachTo: document.body,
  })
  return { wrapper, router }
}

const wrappers: VueWrapper[] = []
function track(wrapper: VueWrapper) {
  wrappers.push(wrapper)
  return wrapper
}

beforeEach(() => {
  vi.clearAllMocks()
  Object.defineProperty(Element.prototype, 'animate', {
    configurable: true,
    writable: true,
    value: vi.fn(() => ({ finished: Promise.resolve(), cancel: vi.fn() })),
  })
  vi.mocked(getOperationsDashboard).mockResolvedValue(dashboard())
})

afterEach(() => {
  while (wrappers.length) wrappers.pop()?.unmount()
  Reflect.deleteProperty(Element.prototype, 'animate')
  document.body.innerHTML = ''
  vi.useRealTimers()
})

describe('运营数据大屏', () => {
  it('展示真实指标、待办和原有经营图表，保留返回入口', async () => {
    const { wrapper, router } = await mountView()
    track(wrapper)
    await flushPromises()

    expect(getOperationsDashboard).toHaveBeenCalledWith(expect.objectContaining({
      timeZone: 'Asia/Shanghai', sourceSystem: 'ALL', receiverType: 'ALL', institutionId: undefined,
    }))
    expect(wrapper.get('.duty-hero-number').attributes('aria-label')).toBe('今日净支付 ¥1211.11')
    expect(wrapper.findAll('.duty-stat-number').map((node) => node.attributes('aria-label'))).toEqual([
      '新增客户 2', '支付订单 3', '到院客户 2', '正式核销 5',
    ])
    expect(wrapper.text()).toContain('6个预约等待机构确认')
    expect(wrapper.text()).toContain('2 项任务已到期')
    expect(wrapper.findAll('.chart-stub')).toHaveLength(1)
    expect(wrapper.find('.duty-nav').exists()).toBe(false)
    expect(wrapper.text()).toContain('交易与退款走势')
    expect(wrapper.text()).toContain('机构履约排行')

    await wrapper.get('.duty-panel-footer button').trigger('click')
    expect(wrapper.findAll('.chart-stub')).toHaveLength(4)
    expect(wrapper.text()).toContain('非转化率')
    expect(wrapper.text()).toContain('净支付 = 支付金额 − 成功退款')
    expect(wrapper.text()).not.toContain('净利润率')

    await wrapper.get('[data-testid="dashboard-back"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/operations')
  })

  it('日期和授权机构筛选传递真实查询参数', async () => {
    const { wrapper } = await mountView()
    track(wrapper)
    await flushPromises()

    await wrapper.get('[data-testid="dashboard-period-day"]').trigger('click')
    await flushPromises()
    expect(getOperationsDashboard).toHaveBeenLastCalledWith(expect.objectContaining({
      from: todayInShanghai(), to: todayInShanghai(),
    }))

    const institution = wrapper.findAllComponents({ name: 'ElSelect' }).at(2)
    expect(institution).toBeDefined()
    await institution!.vm.$emit('update:modelValue', 91)
    await institution!.vm.$emit('change', 91)
    await flushPromises()
    expect(getOperationsDashboard).toHaveBeenLastCalledWith(expect.objectContaining({ institutionId: 91 }))
  })

  it('加载、空态、初始失败和重试都不伪造图表数据', async () => {
    let resolveRequest: ((value: OperationsDashboard) => void) | undefined
    vi.mocked(getOperationsDashboard).mockReturnValueOnce(new Promise((resolve) => { resolveRequest = resolve }))
    const loading = await mountView()
    track(loading.wrapper)
    expect(loading.wrapper.text()).toContain('正在读取授权范围内的真实经营事实')
    resolveRequest?.(emptyDashboard())
    await flushPromises()
    expect(loading.wrapper.text()).toContain('趋势图不会补造零值日期')
    expect(loading.wrapper.text()).toContain('当前范围没有可展示的机构履约数据')
    expect(loading.wrapper.findAll('.chart-stub')).toHaveLength(0)
    expect(loading.wrapper.text()).toContain('先看需要处理的事项')
    vi.mocked(getOperationsDashboard).mockResolvedValueOnce(emptyDashboard())
    await loading.wrapper.get('[data-testid="dashboard-empty-week"]').trigger('click')
    await flushPromises()
    expect(loading.wrapper.text()).toContain('本周经营概览')
    await loading.wrapper.get('.duty-panel-footer button').trigger('click')
    expect(loading.wrapper.text()).toContain('当前范围没有活动成交记录')

    vi.mocked(getOperationsDashboard).mockRejectedValueOnce(new ApiError({ status: 500, code: 'INTERNAL_ERROR', message: '暂时无法读取报表' }))
    const failed = await mountView()
    track(failed.wrapper)
    await flushPromises()
    expect(failed.wrapper.text()).toContain('暂时无法读取报表')
    vi.mocked(getOperationsDashboard).mockResolvedValueOnce(emptyDashboard())
    await failed.wrapper.get('[data-testid="dashboard-retry"]').trigger('click')
    await flushPromises()
    expect(failed.wrapper.text()).toContain('趋势图不会补造零值日期')
  })

  it('刷新失败保留上次成功数据，过期响应不能覆盖新筛选', async () => {
    const { wrapper } = await mountView()
    track(wrapper)
    await flushPromises()
    vi.mocked(getOperationsDashboard).mockRejectedValueOnce(new ApiError({ status: 500, code: 'INTERNAL_ERROR', message: '刷新失败' }))
    await wrapper.get('[data-testid="dashboard-refresh"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('当前展示上次成功数据')
    expect(wrapper.get('.duty-hero-number').attributes('aria-label')).toBe('今日净支付 ¥1211.11')

    let resolveOld: ((value: OperationsDashboard) => void) | undefined
    let resolveNew: ((value: OperationsDashboard) => void) | undefined
    vi.mocked(getOperationsDashboard)
      .mockReturnValueOnce(new Promise((resolve) => { resolveOld = resolve }))
      .mockReturnValueOnce(new Promise((resolve) => { resolveNew = resolve }))
    const stale = await mountView()
    track(stale.wrapper)
    await stale.wrapper.get('[data-testid="dashboard-period-day"]').trigger('click')
    const newer = dashboard()
    newer.metrics.paidFen = 22200
    newer.metrics.netPaidFen = 19855
    resolveNew?.(newer)
    await flushPromises()
    const older = dashboard()
    older.metrics.paidFen = 11100
    older.metrics.netPaidFen = 8755
    resolveOld?.(older)
    await flushPromises()
    expect(stale.wrapper.get('.duty-hero-number').attributes('aria-label')).toBe('今日净支付 ¥198.55')
  })

  it('每 30 秒刷新并在卸载时停止定时器', async () => {
    vi.useFakeTimers()
    const { wrapper } = await mountView()
    track(wrapper)
    await vi.advanceTimersByTimeAsync(0)
    expect(getOperationsDashboard).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(30_000)
    expect(getOperationsDashboard).toHaveBeenCalledTimes(2)
    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(120_000)
    expect(getOperationsDashboard).toHaveBeenCalledTimes(2)
  })

  it('暂停自动刷新后仍可手动刷新，恢复后继续按周期更新', async () => {
    vi.useFakeTimers()
    const { wrapper } = await mountView()
    track(wrapper)
    await vi.advanceTimersByTimeAsync(0)
    expect(getOperationsDashboard).toHaveBeenCalledTimes(1)

    await wrapper.get('[data-testid="dashboard-auto-refresh"]').trigger('click')
    expect(wrapper.get('[data-testid="dashboard-auto-refresh"]').attributes('aria-pressed')).toBe('false')
    await vi.advanceTimersByTimeAsync(60_000)
    expect(getOperationsDashboard).toHaveBeenCalledTimes(1)
    await wrapper.get('[data-testid="dashboard-refresh"]').trigger('click')
    expect(getOperationsDashboard).toHaveBeenCalledTimes(2)
    await wrapper.get('[data-testid="dashboard-auto-refresh"]').trigger('click')
    await vi.advanceTimersByTimeAsync(30_000)
    expect(getOperationsDashboard).toHaveBeenCalledTimes(3)
  })

  it('全屏被浏览器拒绝时明确提示且不伪造全屏状态', async () => {
    const { wrapper } = await mountView()
    track(wrapper)
    await flushPromises()
    const main = wrapper.get('main').element
    main.requestFullscreen = vi.fn().mockRejectedValue(new Error('denied'))

    await wrapper.get('[data-testid="dashboard-fullscreen"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('浏览器未允许全屏')
    expect(wrapper.get('[data-testid="dashboard-fullscreen"]').text()).toBe('全屏查看')
  })

  it('待办先筛选，再次点击进入真实业务列表', async () => {
    const { wrapper, router } = await mountView()
    track(wrapper)
    await flushPromises()

    const appointment = wrapper.get('[aria-label^="6个预约"]')
    await appointment.trigger('click')
    expect(wrapper.findAll('.duty-task')).toHaveLength(1)
    expect(wrapper.text()).toContain('已筛选，再次点击进入处理')
    await appointment.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/appointments')
    expect(router.currentRoute.value.query.status).toBe('PENDING_INSTITUTION')

    const redemption = wrapper.get('[aria-label^="3项服务"]')
    await redemption.trigger('click')
    await redemption.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/appointments')
    expect(router.currentRoute.value.query.status).toBe('SERVICE_DONE')

    const followup = wrapper.get('[aria-label^="8位客户"]')
    await followup.trigger('click')
    await followup.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/tasks')
  })

  it('没有对应权限的计数显示为空，不显示成零或允许进入', async () => {
    vi.mocked(getOperationsDashboard).mockResolvedValueOnce({
      ...dashboard(),
      duty: {
        newCustomerCount: null, pendingTaskCount: null, dueFollowupCount: null, overdueTaskCount: null,
        pendingAppointmentCount: null, awaitingRedemptionCount: null, pendingRefundCount: null,
      },
    })
    const { wrapper } = await mountView()
    track(wrapper)
    await flushPromises()

    expect(wrapper.text()).toContain('当前账号无预约查看权限')
    expect(wrapper.text()).toContain('当前账号无任务查看权限')
    expect(wrapper.text()).not.toContain('0个预约')
    expect(wrapper.get('.duty-task').attributes('disabled')).toBeDefined()
    expect(wrapper.get('.duty-queue-total').text()).toContain('—')
    expect(wrapper.text()).toContain('待确认个预约等待机构确认')
  })
})
