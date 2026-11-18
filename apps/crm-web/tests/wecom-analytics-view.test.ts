import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getWecomAnalytics } from '../src/api/wecomAnalytics'
import type { WecomAnalytics } from '../src/api/wecomAnalytics'
import type { AuthUser } from '../src/api/auth'
import { useAuthStore } from '../src/stores/auth'
import WecomAnalyticsView from '../src/views/WecomAnalyticsView.vue'

vi.mock('../src/api/wecomAnalytics', () => ({ getWecomAnalytics: vi.fn() }))

function report(): WecomAnalytics {
  return {
    from: '2026-11-10', to: '2026-11-11', fromUtc: '2026-11-09T16:00:00Z', toUtcExclusive: '2026-11-11T16:00:00Z',
    timeZone: 'Asia/Shanghai', metricVersion: 'P3_FACT_V1', scopeType: 'SELF', employeeId: null,
    generatedAt: '2026-11-13T08:00:00Z',
    quickReply: { usageCount: 4, copiedCount: 2, sdkSuccessCount: 1, sdkFailedCount: 0, sdkUnknownCount: 1, editedCount: 1, aiLinkedUsageCount: 3, sdkSuccessRate: 50 },
    aiReply: { generatedCount: 3, acceptedCount: 1, rejectedCount: 1, pendingCount: 1, editedAcceptedCount: 1, mockGeneratedCount: 3, copiedCount: 1, sdkSuccessCount: 1, sdkFailedCount: 0, sdkUnknownCount: 1, cohortAcceptanceRate: 33.33 },
    conversion: { confirmedAppointmentCount: 3, checkedInFromConfirmedCount: 2, paidOrderCount: 4, paidCustomerCount: 2, repeatPaidCustomerCount: 1, appointmentArrivalRate: 66.67, repeatCustomerRate: 50 },
    unsupportedMetrics: ['MESSAGE_READ_RATE', 'H5_OPEN_RATE', 'CHAT_CAUSAL_ATTRIBUTION', 'QUICK_REPLY_BUSINESS_OUTCOME'],
  }
}

function mountView(userType = 'EMPLOYEE', permission = true) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = {
    userId: 2, username: 'manager1', displayName: '测试管家', userType,
    roles: userType === 'INSTITUTION' ? ['INSTITUTION_OPERATOR'] : ['MANAGER'],
    permissions: permission ? ['report:read'] : [],
  } as AuthUser
  auth.initialized = true
  return mount(WecomAnalyticsView, {
    global: { plugins: [ElementPlus, pinia], stubs: { RouterLink: { props: ['to'], template: '<a :data-to="to"><slot /></a>' } } },
  })
}

describe('WecomAnalyticsView', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(getWecomAnalytics).mockResolvedValue(report())
  })

  it('展示真实分子分母、数据范围与不支持能力，不混淆复制和采纳', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(getWecomAnalytics).toHaveBeenCalledWith(expect.objectContaining({ from: expect.any(String), to: expect.any(String) }))
    expect(wrapper.text()).toContain('本人负责客户')
    expect(wrapper.get('[data-testid="quick-reply-analysis"]').text()).toContain('50.00%')
    expect(wrapper.get('[data-testid="quick-reply-analysis"]').text()).toContain('复制不是发送')
    expect(wrapper.get('[data-testid="ai-reply-analysis"]').text()).toContain('33.33%')
    expect(wrapper.get('[data-testid="ai-reply-analysis"]').text()).toContain('1 / 3 条创建候选')
    expect(wrapper.get('[data-testid="conversion-analysis"]').text()).toContain('66.67%')
    expect(wrapper.get('[data-testid="conversion-analysis"]').text()).toContain('同一订单多笔流水不重复计数')
    expect(wrapper.text()).toContain('客户已读率')
    expect(wrapper.text()).toContain('H5 打开率')
    expect(wrapper.text()).toContain('不能据此评估真实模型质量')
    expect(wrapper.text()).not.toContain('CHAT_CAUSAL_ATTRIBUTION')
  })

  it('日期错误时不调用接口且清除旧结果', async () => {
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.find('[data-testid="quick-reply-analysis"]').exists()).toBe(true)
    await wrapper.get('[data-testid="analytics-from"]').setValue('2026-11-12')
    await wrapper.get('[data-testid="analytics-to"]').setValue('2026-11-10')
    await wrapper.get('.analytics-filters').trigger('submit')
    await flushPromises()

    expect(getWecomAnalytics).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[data-testid="analytics-error"]').text()).toContain('开始日期不能晚于结束日期')
    expect(wrapper.find('[data-testid="quick-reply-analysis"]').exists()).toBe(false)
  })

  it.each([
    { userType: 'INSTITUTION', permission: true, text: '机构账号无企微客户运营分析入口' },
    { userType: 'EMPLOYEE', permission: false, text: '缺少 report:read 权限' },
  ])('无权限主体不读取分析 ($text)', async ({ userType, permission, text }) => {
    const wrapper = mountView(userType, permission)
    await flushPromises()
    expect(wrapper.text()).toContain(text)
    expect(getWecomAnalytics).not.toHaveBeenCalled()
  })
})
