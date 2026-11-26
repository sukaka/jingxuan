import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessage, ElMessageBox } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { CustomerPrivacyStatus } from '../src/api/customerPrivacy'
import {
  cancelCustomerPrivacyRequest,
  createCustomerPrivacyRequest,
  getCustomerPrivacyStatus,
  revealCustomerPrivacy,
} from '../src/api/customerPrivacy'
import CustomerPrivacyPanel from '../src/views/customer/CustomerPrivacyPanel.vue'

vi.mock('../src/api/customerPrivacy', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../src/api/customerPrivacy')>()),
  getCustomerPrivacyStatus: vi.fn(),
  createCustomerPrivacyRequest: vi.fn(),
  cancelCustomerPrivacyRequest: vi.fn(),
  revealCustomerPrivacy: vi.fn(),
}))

const wrappers: VueWrapper[] = []

function makeBaseStatus(overrides: Partial<CustomerPrivacyStatus> = {}): CustomerPrivacyStatus {
  return {
    customerId: 101,
    fields: [
      { fieldCode: 'MOBILE', label: '手机号', present: true, maskedValue: '138****8000' },
      { fieldCode: 'ID_CARD', label: '身份证号', present: true, maskedValue: '1101**********123X' },
      { fieldCode: 'EMAIL', label: '邮箱', present: true, maskedValue: 'li***@example.com' },
      { fieldCode: 'WECHAT_ID', label: '微信号', present: true, maskedValue: 'wx***88' },
    ],
    canAdminDirectReveal: false,
    canRequestAccess: true,
    canViewAuditLogs: false,
    resolvedApprover: {
      approverUserId: 4,
      approverName: '赵主管',
      approverSource: 'DIRECT_SUPERVISOR',
    },
    pendingRequest: null,
    activeGrant: null,
    allowedDurationMinutes: [15, 30, 120, 1440],
    ...overrides,
  }
}

function mountPanel(props: { customerId?: number; visible?: boolean; readonly?: boolean } = {}) {
  const wrapper = mount(CustomerPrivacyPanel, {
    attachTo: document.body,
    props: {
      customerId: props.customerId ?? 101,
      visible: props.visible ?? true,
      readonly: props.readonly ?? false,
    },
    global: {
      plugins: [ElementPlus],
    },
  })
  wrappers.push(wrapper)
  return wrapper
}

beforeEach(() => {
  vi.mocked(getCustomerPrivacyStatus).mockReset()
  vi.mocked(createCustomerPrivacyRequest).mockReset()
  vi.mocked(cancelCustomerPrivacyRequest).mockReset()
  vi.mocked(revealCustomerPrivacy).mockReset()
  vi.spyOn(ElMessage, 'warning').mockImplementation(() => undefined as never)
  vi.spyOn(ElMessage, 'success').mockImplementation(() => undefined as never)
  vi.spyOn(ElMessage, 'error').mockImplementation(() => undefined as never)
})

afterEach(() => {
  while (wrappers.length) wrappers.pop()?.unmount()
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})

describe('CustomerPrivacyPanel 客户隐私保护与显式揭盲', () => {
  it('默认对所有角色（含 ADMIN）仅渲染脱敏值，不暴露任何明文', async () => {
    vi.mocked(getCustomerPrivacyStatus).mockResolvedValue(
      makeBaseStatus({
        canAdminDirectReveal: true,
        canRequestAccess: false,
        canViewAuditLogs: true,
        resolvedApprover: null,
      }),
    )

    const wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.get('[data-testid="privacy-field-MOBILE"]').text()).toContain('138****8000')
    expect(wrapper.get('[data-testid="privacy-field-ID_CARD"]').text()).toContain('1101**********123X')
    expect(wrapper.get('[data-testid="privacy-field-EMAIL"]').text()).toContain('li***@example.com')
    expect(wrapper.get('[data-testid="privacy-field-WECHAT_ID"]').text()).toContain('wx***88')
    expect(wrapper.find('[data-testid="privacy-admin-reveal-btn"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="privacy-audit-banner"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('13800138000')
  })

  it('ADMIN 点击按钮必须填写查看原因，提交后临时展开明文，手动隐藏或关闭抽屉立即销毁明文', async () => {
    vi.mocked(getCustomerPrivacyStatus).mockResolvedValue(
      makeBaseStatus({
        canAdminDirectReveal: true,
        canRequestAccess: false,
        canViewAuditLogs: true,
        resolvedApprover: null,
      }),
    )
    vi.mocked(revealCustomerPrivacy).mockResolvedValue({
      customerId: 101,
      accessMode: 'ADMIN_DIRECT_REVEAL',
      requestId: null,
      requestNo: null,
      revealedFields: ['MOBILE', 'ID_CARD', 'EMAIL', 'WECHAT_ID'],
      values: {
        MOBILE: '13800138000',
        ID_CARD: '11010519491231002X',
        EMAIL: 'linwan@example.com',
        WECHAT_ID: 'wx_linwan88',
      },
      revealedAt: '2026-11-26T10:00:00+08:00',
      grantExpiresAt: null,
      autoHideSeconds: 120,
      accessLogId: 9001,
    })

    const wrapper = mountPanel()
    await flushPromises()

    await wrapper.get('[data-testid="privacy-admin-reveal-btn"]').trigger('click')
    await flushPromises()

    // 空原因拦截
    document.querySelector<HTMLButtonElement>('[data-testid="privacy-admin-reveal-submit"]')!.click()
    await flushPromises()
    expect(revealCustomerPrivacy).not.toHaveBeenCalled()
    expect(ElMessage.warning).toHaveBeenCalled()

    // 填写原因后提交
    const reasonInput = document.querySelector<HTMLTextAreaElement>('[data-testid="privacy-admin-reason-input"]')!
    reasonInput.value = '核查客户售后回访联系方式'
    reasonInput.dispatchEvent(new Event('input'))
    await flushPromises()

    document.querySelector<HTMLButtonElement>('[data-testid="privacy-admin-reveal-submit"]')!.click()
    await flushPromises()

    expect(revealCustomerPrivacy).toHaveBeenCalledWith(101, {
      fields: ['MOBILE', 'ID_CARD', 'EMAIL', 'WECHAT_ID'],
      reason: '核查客户售后回访联系方式',
      mode: 'ADMIN_DIRECT',
    })
    expect(wrapper.get('[data-testid="privacy-field-MOBILE"]').text()).toContain('13800138000')
    expect(wrapper.get('[data-testid="privacy-field-ID_CARD"]').text()).toContain('11010519491231002X')
    expect(wrapper.get('[data-testid="privacy-audit-banner"]').text()).toContain('#9001')
    expect(wrapper.find('[data-testid="privacy-autohide-countdown"]').exists()).toBe(true)

    // 手动隐藏后明文立即销毁并恢复脱敏
    await wrapper.get('[data-testid="privacy-hide-btn"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('13800138000')
    expect(wrapper.get('[data-testid="privacy-field-MOBILE"]').text()).toContain('138****8000')

    // 再次展开后关闭抽屉（visible=false）同样销毁明文
    await wrapper.get('[data-testid="privacy-admin-reveal-btn"]').trigger('click')
    await flushPromises()
    const reasonInput2 = document.querySelector<HTMLTextAreaElement>('[data-testid="privacy-admin-reason-input"]')!
    reasonInput2.value = '再次核查'
    reasonInput2.dispatchEvent(new Event('input'))
    await flushPromises()
    document.querySelector<HTMLButtonElement>('[data-testid="privacy-admin-reveal-submit"]')!.click()
    await flushPromises()
    expect(wrapper.text()).toContain('13800138000')

    await wrapper.setProps({ visible: false })
    await flushPromises()
    expect(wrapper.text()).not.toContain('13800138000')
  })

  it('非 ADMIN 可向上级发起申请，并展示待审批状态与撤回按钮', async () => {
    const pendingReq = {
      id: 55,
      requestNo: 'PR202611260001',
      customerId: 101,
      customerNo: 'C001',
      customerName: '林晚',
      applicantUserId: 2,
      applicantName: '王丽',
      applicantTeamId: 1,
      requestedFields: ['MOBILE', 'EMAIL'] as const,
      approvedFields: [] as const,
      reason: '预约术前确认',
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
    vi.mocked(getCustomerPrivacyStatus)
      .mockResolvedValueOnce(makeBaseStatus())
      .mockResolvedValueOnce(
        makeBaseStatus({
          pendingRequest: {
            ...pendingReq,
            requestedFields: [...pendingReq.requestedFields],
            approvedFields: [],
          },
        }),
      )
      .mockResolvedValueOnce(makeBaseStatus())
    vi.mocked(createCustomerPrivacyRequest).mockResolvedValue({
      ...pendingReq,
      requestedFields: [...pendingReq.requestedFields],
      approvedFields: [],
    })
    vi.mocked(cancelCustomerPrivacyRequest).mockResolvedValue({
      ...pendingReq,
      requestedFields: [...pendingReq.requestedFields],
      approvedFields: [],
      status: 'CANCELED',
      effectiveStatus: 'CANCELED',
      version: 1,
    })
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)

    const wrapper = mountPanel()
    await flushPromises()

    await wrapper.get('[data-testid="privacy-request-btn"]').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('审批上级：赵主管（直属上级）')

    const reasonInput = document.querySelector<HTMLTextAreaElement>('[data-testid="privacy-request-reason-input"]')!
    reasonInput.value = '预约术前确认'
    reasonInput.dispatchEvent(new Event('input'))
    await flushPromises()

    document.querySelector<HTMLButtonElement>('[data-testid="privacy-request-submit"]')!.click()
    await flushPromises()

    expect(createCustomerPrivacyRequest).toHaveBeenCalledWith(
      101,
      {
        fields: ['MOBILE', 'ID_CARD', 'EMAIL', 'WECHAT_ID'],
        durationMinutes: 30,
        reason: '预约术前确认',
      },
      expect.any(String),
    )
    expect(wrapper.get('[data-testid="privacy-pending-banner"]').text()).toContain('PR202611260001')

    await wrapper.get('[data-testid="privacy-cancel-request-btn"]').trigger('click')
    await flushPromises()
    expect(cancelCustomerPrivacyRequest).toHaveBeenCalledWith(55, 0)
  })

  it('非 ADMIN 审批通过后必须点击打开查看明文按钮，且仅展示获批字段', async () => {
    const activeGrant = {
      id: 66,
      requestNo: 'PR202611260002',
      customerId: 101,
      customerNo: 'C001',
      customerName: '林晚',
      applicantUserId: 2,
      applicantName: '王丽',
      applicantTeamId: 1,
      requestedFields: ['MOBILE', 'ID_CARD'] as ('MOBILE' | 'ID_CARD')[],
      approvedFields: ['MOBILE'] as ('MOBILE')[],
      reason: '电话确认复诊时间',
      status: 'APPROVED',
      effectiveStatus: 'APPROVED',
      assignedApproverId: 4,
      assignedApproverName: '赵主管',
      approverSource: 'DIRECT_SUPERVISOR',
      requestedDurationMinutes: 30,
      approvedDurationMinutes: 30,
      reviewedBy: 4,
      reviewedByName: '赵主管',
      reviewedAt: '2026-11-26T10:05:00+08:00',
      reviewReason: '仅批准手机号',
      grantStartsAt: '2026-11-26T10:05:00+08:00',
      grantExpiresAt: '2026-11-26T10:35:00+08:00',
      remainingSeconds: 1800,
      revokedBy: null,
      revokedByName: null,
      revokedAt: null,
      revokeReason: null,
      revealCount: 0,
      lastRevealedAt: null,
      createdAt: '2026-11-26T10:00:00+08:00',
      version: 1,
    }
    vi.mocked(getCustomerPrivacyStatus).mockResolvedValue(
      makeBaseStatus({ activeGrant }),
    )
    vi.mocked(revealCustomerPrivacy).mockResolvedValue({
      customerId: 101,
      accessMode: 'APPROVED_GRANT_REVEAL',
      requestId: 66,
      requestNo: 'PR202611260002',
      revealedFields: ['MOBILE'],
      values: { MOBILE: '13800138000' },
      revealedAt: '2026-11-26T10:06:00+08:00',
      grantExpiresAt: '2026-11-26T10:35:00+08:00',
      autoHideSeconds: 120,
      accessLogId: 9002,
    })

    const wrapper = mountPanel()
    await flushPromises()

    // 审批通过但尚未点击按钮时，依然只显示脱敏值
    expect(wrapper.get('[data-testid="privacy-field-MOBILE"]').text()).toContain('138****8000')
    expect(wrapper.get('[data-testid="privacy-grant-banner"]').text()).toContain('PR202611260002')

    await wrapper.get('[data-testid="privacy-grant-reveal-btn"]').trigger('click')
    await flushPromises()

    expect(revealCustomerPrivacy).toHaveBeenCalledWith(101, {
      fields: ['MOBILE'],
      requestId: 66,
      mode: 'APPROVED_GRANT',
    })
    // 获批字段展示明文，未获批字段保持脱敏
    expect(wrapper.get('[data-testid="privacy-field-MOBILE"]').text()).toContain('13800138000')
    expect(wrapper.get('[data-testid="privacy-field-ID_CARD"]').text()).toContain('1101**********123X')
    expect(wrapper.get('[data-testid="privacy-audit-banner"]').text()).toContain('#9002')
  })
})
