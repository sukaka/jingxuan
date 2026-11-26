import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessage, ElMessageBox } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { CustomerPrivacyAccessLogItem, CustomerPrivacyRequestItem } from '../src/api/customerPrivacy'
import {
  approveCustomerPrivacyRequest,
  cancelCustomerPrivacyRequest,
  listCustomerPrivacyAccessLogs,
  listCustomerPrivacyRequests,
  rejectCustomerPrivacyRequest,
  revokeCustomerPrivacyRequest,
} from '../src/api/customerPrivacy'
import { useAuthStore } from '../src/stores/auth'
import CustomerPrivacyApprovalsView from '../src/views/CustomerPrivacyApprovalsView.vue'

vi.mock('../src/api/customerPrivacy', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../src/api/customerPrivacy')>()),
  listCustomerPrivacyRequests: vi.fn(),
  approveCustomerPrivacyRequest: vi.fn(),
  rejectCustomerPrivacyRequest: vi.fn(),
  revokeCustomerPrivacyRequest: vi.fn(),
  cancelCustomerPrivacyRequest: vi.fn(),
  listCustomerPrivacyAccessLogs: vi.fn(),
}))

const wrappers: VueWrapper[] = []

const pendingRow: CustomerPrivacyRequestItem = {
  id: 10,
  requestNo: 'PR202611260010',
  customerId: 101,
  customerNo: 'C000101',
  customerName: '林晚',
  applicantUserId: 2,
  applicantName: '王丽',
  applicantTeamId: 1,
  requestedFields: ['MOBILE', 'ID_CARD'],
  approvedFields: [],
  reason: '电话确认术后复查',
  status: 'PENDING',
  effectiveStatus: 'PENDING',
  assignedApproverId: 4,
  assignedApproverName: '赵主管',
  approverSource: 'DIRECT_SUPERVISOR',
  requestedDurationMinutes: 30,
  approvedDurationMinutes: null,
  reviewedBy: null,
  reviewedByName: null,
  reviewedAt: null,
  reviewReason: null,
  grantStartsAt: null,
  grantExpiresAt: null,
  remainingSeconds: null,
  revokedBy: null,
  revokedByName: null,
  revokedAt: null,
  revokeReason: null,
  revealCount: 0,
  lastRevealedAt: null,
  createdAt: '2026-11-26T10:00:00+08:00',
  version: 0,
}

const approvedRow: CustomerPrivacyRequestItem = {
  ...pendingRow,
  id: 11,
  requestNo: 'PR202611260011',
  approvedFields: ['MOBILE'],
  status: 'APPROVED',
  effectiveStatus: 'APPROVED',
  approvedDurationMinutes: 30,
  reviewedBy: 4,
  reviewedByName: '赵主管',
  reviewedAt: '2026-11-26T10:05:00+08:00',
  grantStartsAt: '2026-11-26T10:05:00+08:00',
  grantExpiresAt: '2026-11-26T10:35:00+08:00',
  remainingSeconds: 1200,
  revealCount: 1,
  version: 1,
}

const sampleLog: CustomerPrivacyAccessLogItem = {
  id: 501,
  customerId: 101,
  customerNo: 'C000101',
  customerName: '林晚',
  actorUserId: 1,
  actorUsername: 'admin',
  actorDisplayName: '系统管理员',
  actorRoleSnapshot: 'ADMIN',
  accessMode: 'ADMIN_DIRECT_REVEAL',
  requestId: null,
  requestNo: null,
  revealedFields: ['MOBILE', 'EMAIL'],
  maskedValuesSnapshot: {
    MOBILE: '138****8000',
    EMAIL: 'li***@example.com',
  },
  reason: '客诉回访核查',
  clientIp: '10.0.0.8',
  userAgent: 'Vitest',
  traceId: 'trace-501',
  createdAt: '2026-11-26T10:10:00+08:00',
}

async function mountView(permissions: string[], roles: string[], query: Record<string, string> = {}) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/customers/privacy-approvals', component: CustomerPrivacyApprovalsView }],
  })
  await router.push({ path: '/customers/privacy-approvals', query })
  await router.isReady()

  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore(pinia)
  auth.user = {
    userId: 1,
    username: roles[0]?.toLowerCase() ?? 'user',
    displayName: '测试用户',
    userType: 'EMPLOYEE',
    roles,
    permissions,
  }
  auth.initialized = true

  const wrapper = mount(CustomerPrivacyApprovalsView, {
    attachTo: document.body,
    global: {
      plugins: [router, pinia, ElementPlus],
    },
  })
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  vi.mocked(listCustomerPrivacyRequests).mockReset().mockResolvedValue({
    items: [pendingRow, approvedRow],
    total: 2,
    page: 1,
    size: 20,
  })
  vi.mocked(approveCustomerPrivacyRequest).mockReset().mockResolvedValue(approvedRow)
  vi.mocked(rejectCustomerPrivacyRequest).mockReset().mockResolvedValue({
    ...pendingRow,
    status: 'REJECTED',
    effectiveStatus: 'REJECTED',
    version: 1,
  })
  vi.mocked(revokeCustomerPrivacyRequest).mockReset().mockResolvedValue({
    ...approvedRow,
    status: 'REVOKED',
    effectiveStatus: 'REVOKED',
    version: 2,
  })
  vi.mocked(cancelCustomerPrivacyRequest).mockReset().mockResolvedValue({
    ...pendingRow,
    status: 'CANCELED',
    effectiveStatus: 'CANCELED',
    version: 1,
  })
  vi.mocked(listCustomerPrivacyAccessLogs).mockReset().mockResolvedValue({
    items: [sampleLog],
    total: 1,
    page: 1,
    size: 20,
  })
  vi.spyOn(ElMessage, 'warning').mockImplementation(() => undefined as never)
  vi.spyOn(ElMessage, 'success').mockImplementation(() => undefined as never)
  vi.spyOn(ElMessage, 'error').mockImplementation(() => undefined as never)
})

afterEach(() => {
  while (wrappers.length) wrappers.pop()?.unmount()
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})

describe('CustomerPrivacyApprovalsView 隐私审批与访问日志中心', () => {
  it('审批主管加载待审列表并可执行批准、驳回和撤销授权', async () => {
    const wrapper = await mountView(
      ['customer:privacy:approve', 'customer:privacy:request', 'customer:privacy:audit'],
      ['MANAGER_LEAD'],
    )

    expect(listCustomerPrivacyRequests).toHaveBeenCalledWith({
      page: 1,
      size: 20,
      view: 'PENDING_APPROVAL',
      status: 'PENDING',
      keyword: undefined,
    })
    expect(wrapper.text()).toContain('PR202611260010')
    expect(wrapper.text()).toContain('林晚')
    expect(wrapper.text()).toContain('王丽')

    // 批准待审单
    await wrapper.get('[data-testid="privacy-approve-btn"]').trigger('click')
    await flushPromises()
    document.querySelector<HTMLButtonElement>('[data-testid="confirm-approve-btn"]')!.click()
    await flushPromises()
    expect(approveCustomerPrivacyRequest).toHaveBeenCalledWith(10, {
      approvedFields: ['MOBILE', 'ID_CARD'],
      durationMinutes: 30,
      reviewReason: undefined,
      version: 0,
    })

    // 驳回待审单（空原因拦下，填写后通过）
    await wrapper.get('[data-testid="privacy-reject-btn"]').trigger('click')
    await flushPromises()
    document.querySelector<HTMLButtonElement>('[data-testid="confirm-reject-btn"]')!.click()
    await flushPromises()
    expect(rejectCustomerPrivacyRequest).not.toHaveBeenCalled()

    const rejectTextarea = document.querySelector<HTMLTextAreaElement>('[data-testid="reject-reason-input"]')!
    rejectTextarea.value = '申请理由不充分'
    rejectTextarea.dispatchEvent(new Event('input'))
    await flushPromises()
    document.querySelector<HTMLButtonElement>('[data-testid="confirm-reject-btn"]')!.click()
    await flushPromises()
    expect(rejectCustomerPrivacyRequest).toHaveBeenCalledWith(10, {
      reviewReason: '申请理由不充分',
      version: 0,
    })

    // 撤销已生效授权
    await wrapper.get('[data-testid="privacy-revoke-btn"]').trigger('click')
    await flushPromises()
    const revokeTextarea = document.querySelector<HTMLTextAreaElement>('[data-testid="revoke-reason-input"]')!
    revokeTextarea.value = '核查已完成，提前回收'
    revokeTextarea.dispatchEvent(new Event('input'))
    await flushPromises()
    document.querySelector<HTMLButtonElement>('[data-testid="confirm-revoke-btn"]')!.click()
    await flushPromises()
    expect(revokeCustomerPrivacyRequest).toHaveBeenCalledWith(11, {
      revokeReason: '核查已完成，提前回收',
      version: 1,
    })
  })

  it('审计日志页签展示管理员按钮揭盲与脱敏快照，且不出现任何明文', async () => {
    const wrapper = await mountView(
      ['customer:privacy:approve', 'customer:privacy:audit'],
      ['ADMIN'],
      { tab: 'logs' },
    )

    expect(listCustomerPrivacyAccessLogs).toHaveBeenCalledWith({
      page: 1,
      size: 20,
      customerId: undefined,
      actorUserId: undefined,
      accessMode: undefined,
    })
    expect(wrapper.text()).toContain('系统管理员')
    expect(wrapper.text()).toContain('管理员按钮揭盲')
    expect(wrapper.text()).toContain('手机号：138****8000')
    expect(wrapper.text()).toContain('邮箱：li***@example.com')
    expect(wrapper.text()).toContain('客诉回访核查')
    expect(wrapper.text()).toContain('trace-501')
  })

  it('普通管家仅显示我的隐私申请并支持撤回待审单', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const wrapper = await mountView(['customer:privacy:request'], ['MANAGER'])

    expect(listCustomerPrivacyRequests).toHaveBeenCalledWith({
      page: 1,
      size: 20,
      view: 'MY',
      status: undefined,
      keyword: undefined,
    })
    expect(wrapper.text()).toContain('我的隐私申请')
    expect(wrapper.text()).not.toContain('隐私访问审计日志')

    const cancelBtn = wrapper.findAllComponents({ name: 'ElButton' }).find((btn) => btn.text().trim() === '撤回')
    expect(cancelBtn).toBeDefined()
    await cancelBtn!.trigger('click')
    await flushPromises()
    expect(cancelCustomerPrivacyRequest).toHaveBeenCalledWith(10, 0)
  })
})
