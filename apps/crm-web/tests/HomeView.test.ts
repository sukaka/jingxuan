import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuthUser } from '../src/api/auth'
import { listCustomers } from '../src/api/customer'
import { listFollowups } from '../src/api/followup'
import { getRedemption, listAppointments, listRedemptions } from '../src/api/fulfillment'
import type { Appointment, RedemptionDetail } from '../src/api/fulfillment'
import { listTasks } from '../src/api/task'
import { getPerformanceReport } from '../src/api/report'
import type { PerformanceReport } from '../src/api/report'
import { getEntitlementSummary } from '../src/api/trading'
import { useAuthStore } from '../src/stores/auth'
import { ApiError } from '../src/types/api'
import HomeView from '../src/views/HomeView.vue'

vi.mock('../src/api/customer', () => ({ listCustomers: vi.fn(), getCustomer: vi.fn().mockRejectedValue(new Error('no customer in test')) }))
vi.mock('../src/api/task', () => ({ listTasks: vi.fn() }))
vi.mock('../src/api/followup', () => ({ listFollowups: vi.fn() }))
vi.mock('../src/api/fulfillment', () => ({
  listAppointments: vi.fn(),
  listRedemptions: vi.fn(),
  getRedemption: vi.fn(),
}))
vi.mock('../src/api/report', () => ({ getPerformanceReport: vi.fn() }))
vi.mock('../src/api/trading', () => ({ getEntitlementSummary: vi.fn() }))

const MANAGER_PERMISSIONS = ['customer:read', 'task:read', 'followup:read', 'appointment:read']

function emptyPage<T>(size: number) {
  return { items: [] as T[], total: 0, page: 1, size }
}

function appointment(id: number, status: Appointment['status'], scheduledAt: string): Appointment {
  return {
    id,
    appointmentNo: `APT-${id}`,
    customerId: 80 + id,
    institutionId: 9,
    ownerStaffId: 1,
    teamId: null,
    scheduledAt,
    remark: null,
    status,
    createdBy: 1,
    createdAt: '2026-10-23T09:00:00+08:00',
    version: 1,
  }
}

function redemptionDetail(): RedemptionDetail {
  return {
    redemption: {
      id: 701,
      appointmentId: 2,
      customerId: 82,
      institutionId: 9,
      ownerStaffId: 1,
      teamId: null,
      serviceAt: '2026-10-24T10:00:00+08:00',
      evidenceRef: 'SERVICE-EVIDENCE-TEST',
      voucherFileId: null,
      status: 'POSTED',
      settlementStatus: 'PENDING_VERIFICATION',
      idempotencyKey: 'home-redemption-test',
      postedBy: 1,
      postedAt: '2026-10-24T11:00:00+08:00',
      createdAt: '2026-10-24T11:00:00+08:00',
      version: 1,
    },
    lines: [
      { id: 1, redemptionId: 701, appointmentItemId: 11, entitlementId: 21, projectId: 31, count: 2, createdAt: '2026-10-24T11:00:00+08:00' },
      { id: 2, redemptionId: 701, appointmentItemId: 12, entitlementId: 22, projectId: 32, count: 3, createdAt: '2026-10-24T11:00:00+08:00' },
    ],
    history: [],
  }
}

function mountHomeView(
  permissions = ['task:read'],
  roles = ['ADMIN'],
  userType = 'EMPLOYEE',
) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore(pinia)
  const user: AuthUser = {
    userId: 1,
    username: 'tester',
    displayName: '测试账号',
    userType,
    roles,
    permissions,
  }
  auth.user = user
  auth.initialized = true
  return mount(HomeView, {
    global: {
      plugins: [pinia, ElementPlus],
      stubs: { RouterLink: { template: '<a><slot /></a>' } },
    },
  })
}

function statValue(wrapper: VueWrapper, label: string): string {
  const card = wrapper.findAll('.proto-stat-card').find((item) => item.text().includes(label))
  expect(card, `缺少指标卡：${label}`).toBeDefined()
  return card?.get('.number').text() ?? ''
}

beforeEach(() => {
  vi.mocked(listCustomers).mockReset().mockResolvedValue(emptyPage(5))
  vi.mocked(listTasks).mockReset().mockResolvedValue(emptyPage(6))
  vi.mocked(listFollowups).mockReset().mockResolvedValue(emptyPage(5))
  vi.mocked(listAppointments).mockReset().mockResolvedValue(emptyPage(100))
  vi.mocked(listRedemptions).mockReset().mockResolvedValue(emptyPage(100))
  vi.mocked(getRedemption).mockReset()
  vi.mocked(getPerformanceReport).mockReset()
  vi.mocked(getEntitlementSummary).mockReset()
})

describe('HomeView 分角色真实数据工作台', () => {
  it('管家工作台展示各接口真实 total、任务和跟进记录', async () => {
    vi.mocked(listCustomers).mockResolvedValue({ items: [], total: 12, page: 1, size: 5 })
    vi.mocked(listTasks).mockResolvedValue({
      items: [{
        id: 1,
        customerId: 8,
        assigneeUserId: 1,
        taskType: 'FOLLOWUP',
        priority: 'HIGH',
        status: 'OPEN',
        title: '确认回访时间',
        description: null,
        dueAt: '2026-10-24T10:00:00+08:00',
        createdAt: '2026-10-23T09:00:00+08:00',
        version: 1,
      }],
      total: 3,
      page: 1,
      size: 6,
    })
    vi.mocked(listFollowups).mockResolvedValue({
      items: [{
        id: 2,
        customerId: 8,
        customerName: '测试客户',
        ownerStaffId: 1,
        channelCode: 'PHONE',
        contactAt: '2026-10-24T09:30:00+08:00',
        summary: '已确认下次沟通时间',
        intentProject: null,
        estimatedAmountFen: 0,
        nextFollowupAt: null,
        blockerNote: null,
        createdBy: 1,
        createdAt: '2026-10-24T09:31:00+08:00',
        version: 1,
      }],
      total: 2,
      page: 1,
      size: 5,
    })
    vi.mocked(listAppointments).mockResolvedValue({ items: [], total: 4, page: 1, size: 100 })

    const wrapper = mountHomeView(MANAGER_PERMISSIONS, ['MANAGER'])
    await flushPromises()

    expect(wrapper.text()).toContain('早上好，测试账号')
    expect(wrapper.findAll('.work-bucket h3').map((item) => item.text())).toEqual(['3', '4', '12', '2'])
    expect(wrapper.findAll('.manager-kpi h3').map((item) => item.text())).toEqual(['12 位', '3 项', '—', '—'])
    expect(wrapper.text()).toContain('确认回访时间')
    expect(wrapper.text()).toContain('已确认下次沟通时间')
    expect(listCustomers).toHaveBeenCalledWith({ page: 1, size: 5 })
    expect(listTasks).toHaveBeenCalledWith({ page: 1, size: 6, status: 'OPEN' })
    expect(listFollowups).toHaveBeenCalledWith({ page: 1, size: 5 })
    expect(listAppointments).toHaveBeenCalledWith({ page: 1, size: 100 })
    expect(listRedemptions).not.toHaveBeenCalled()
    expect(getPerformanceReport).not.toHaveBeenCalled()
    expect(getEntitlementSummary).not.toHaveBeenCalled()
  })

  it('管家本月业绩使用净支付口径，剩余服务使用全量权益汇总', async () => {
    const totals = {
      paidOrderCount: 4,
      refundCount: 1,
      paidFen: 128000,
      platformPaidFen: 128000,
      institutionPaidFen: 0,
      refundFen: 28000,
      platformRefundFen: 28000,
      institutionRefundFen: 0,
      netPaidFen: 100000,
      platformNetPaidFen: 100000,
      institutionNetPaidFen: 0,
    }
    vi.mocked(getPerformanceReport).mockResolvedValue({ totals } as PerformanceReport)
    vi.mocked(getEntitlementSummary).mockResolvedValue({ availableCount: 9, customerCount: 3 })

    const wrapper = mountHomeView([...MANAGER_PERMISSIONS, 'report:read', 'entitlement:read'], ['MANAGER'])
    await flushPromises()

    expect(wrapper.get('[data-testid="manager-monthly-performance"] h3').text()).toBe('¥1,000.00')
    expect(wrapper.get('[data-testid="manager-monthly-performance"]').text()).toContain('支付 ¥1,280.00 · 退款 ¥280.00')
    expect(wrapper.get('[data-testid="manager-remaining-services"] h3').text()).toBe('9 次')
    expect(wrapper.get('[data-testid="manager-remaining-services"]').text()).toContain('3 位客户的有效可用权益')
    expect(wrapper.text()).not.toContain('待接入')
    expect(getPerformanceReport).toHaveBeenCalledWith(expect.objectContaining({
      timeZone: 'Asia/Shanghai', performanceRole: 'MANAGER', page: 1, size: 1,
    }))
    const query = vi.mocked(getPerformanceReport).mock.calls[0]?.[0]
    expect(query?.from).toMatch(/^\d{4}-\d{2}-01$/)
    expect(query?.to).toMatch(/^\d{4}-\d{2}-\d{2}$/)
    expect(getEntitlementSummary).toHaveBeenCalledWith()
  })

  it('机构工作台按预约状态统计，并从正式核销详情累计次数', async () => {
    const today = new Date().toISOString()
    vi.mocked(listAppointments).mockResolvedValue({
      items: [
        appointment(1, 'PENDING_INSTITUTION', today),
        appointment(2, 'SERVICE_DONE', today),
        appointment(3, 'CONFIRMED', '2030-01-02T10:00:00+08:00'),
      ],
      total: 9,
      page: 1,
      size: 100,
    })
    vi.mocked(listRedemptions).mockResolvedValue({
      items: [redemptionDetail().redemption],
      total: 1,
      page: 1,
      size: 100,
    })
    vi.mocked(getRedemption).mockResolvedValue(redemptionDetail())

    const wrapper = mountHomeView(
      ['appointment:read', 'redemption:read'],
      ['INSTITUTION_OPERATOR'],
      'INSTITUTION',
    )
    await flushPromises()

    expect(wrapper.text()).toContain('合作机构工作台')
    expect(wrapper.text()).toContain('1 个新预约待机构确认，1 项服务待核销确认')
    expect(statValue(wrapper, '待确认预约')).toBe('1 项')
    expect(statValue(wrapper, '今日接待')).toBe('2 位')
    expect(statValue(wrapper, '待核销确认')).toBe('1 项')
    expect(statValue(wrapper, '累计正式核销')).toBe('5 次')
    expect(wrapper.text()).toContain('APT-1')
    expect(listAppointments).toHaveBeenCalledWith({ page: 1, size: 100 })
    expect(listRedemptions).toHaveBeenCalledWith({ page: 1, size: 100, status: 'POSTED' })
    expect(getRedemption).toHaveBeenCalledWith(701)
  })

  it('运营工作台聚焦真实待办和业务导航，不展示研发进度或未接入占位卡', async () => {
    vi.mocked(listTasks).mockResolvedValue({
      items: [{
        id: 5,
        customerId: null,
        assigneeUserId: 1,
        taskType: 'FOLLOWUP',
        priority: 'MEDIUM',
        status: 'OPEN',
        title: '主管复核任务',
        description: '核对实际履约记录',
        dueAt: null,
        createdAt: '2026-10-23T09:00:00+08:00',
        version: 1,
      }],
      total: 6,
      page: 1,
      size: 6,
    })

    const wrapper = mountHomeView()
    await flushPromises()

    expect(wrapper.text()).toContain('运营工作台')
    expect(wrapper.text()).not.toContain('运营工作台 · V1.7')
    expect(wrapper.get('.admin-hero h2').text()).toContain('6')
    expect(wrapper.get('.admin-hero h2').text()).toContain('项待办')
    expect(wrapper.text()).toContain('团队待办')
    expect(wrapper.text()).toContain('快捷入口')
    expect(wrapper.text()).toContain('主管复核任务')
    expect(wrapper.text()).toContain('核对实际履约记录')
    expect(wrapper.text()).not.toContain('反馈整改状态')
    expect(wrapper.text()).not.toContain('待接入')
    expect(wrapper.text()).not.toContain('模拟已验收')
    expect(wrapper.text()).not.toContain('超过 24 小时未回复')
    expect(wrapper.findAll('.admin-nav-item')).toHaveLength(6)
    expect(wrapper.find('.proto-stat-card').exists()).toBe(false)
    expect(listTasks).toHaveBeenCalledWith({ page: 1, size: 6, status: 'OPEN' })
    expect(listCustomers).not.toHaveBeenCalled()
    expect(listAppointments).not.toHaveBeenCalled()
  })

  it('运营与机构空数据分别显示真实空态和零计数', async () => {
    const admin = mountHomeView()
    await flushPromises()
    expect(admin.text()).toContain('暂无待办任务')
    admin.unmount()

    const institution = mountHomeView(
      ['appointment:read', 'redemption:read'],
      ['INSTITUTION_OPERATOR'],
      'INSTITUTION',
    )
    await flushPromises()
    expect(institution.text()).toContain('0 个新预约待机构确认，0 项服务待核销确认')
    expect(institution.text()).toContain('当前没有机构待处理提醒')
    expect(statValue(institution, '累计正式核销')).toBe('0 次')
    expect(getRedemption).not.toHaveBeenCalled()
  })

  it('管家仅有客户权限时不请求其他模块，并显示客户接口 total', async () => {
    vi.mocked(listCustomers).mockResolvedValue({ items: [], total: 8, page: 1, size: 5 })
    const wrapper = mountHomeView(['customer:read'], ['MANAGER'])
    await flushPromises()

    expect(listCustomers).toHaveBeenCalledTimes(1)
    expect(listTasks).not.toHaveBeenCalled()
    expect(listFollowups).not.toHaveBeenCalled()
    expect(listAppointments).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('系统只显示你负责的 8 位客户')
    expect(wrapper.findAll('.work-bucket h3').map((item) => item.text())).toEqual(['0', '0', '8', '0'])
  })

  it('单个接口失败展示真实错误，其他成功模块仍保留接口数据', async () => {
    vi.mocked(listCustomers).mockRejectedValue(
      new ApiError({ status: 403, code: 'FORBIDDEN', message: '无权访问客户数据' }),
    )
    vi.mocked(listTasks).mockResolvedValue({ items: [], total: 3, page: 1, size: 6 })
    vi.mocked(listFollowups).mockResolvedValue({ items: [], total: 2, page: 1, size: 5 })
    vi.mocked(listAppointments).mockResolvedValue({ items: [], total: 4, page: 1, size: 100 })

    const wrapper = mountHomeView(MANAGER_PERMISSIONS, ['MANAGER'])
    await flushPromises()

    expect(wrapper.get('.el-alert').text()).toContain('无权访问客户数据')
    expect(wrapper.findAll('.work-bucket h3').map((item) => item.text())).toEqual(['3', '4', '0', '2'])
  })

  it('网络失败显示后端错误，不调用旧健康检查或生成演示记录', async () => {
    vi.mocked(listTasks).mockRejectedValue(
      new ApiError({ status: 0, code: 'NETWORK_ERROR', message: '无法连接后端服务' }),
    )
    const wrapper = mountHomeView()
    await flushPromises()

    expect(wrapper.get('.el-alert').text()).toContain('无法连接后端服务')
    expect(wrapper.find('.admin-task-card').exists()).toBe(true)
    expect(wrapper.findAll('.admin-task-card .el-table__row')).toHaveLength(0)
    expect(wrapper.text()).toContain('暂无待办任务')
    expect(listTasks).toHaveBeenCalledTimes(1)
  })
})
