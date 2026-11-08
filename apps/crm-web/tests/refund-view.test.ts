import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuthUser } from '../src/api/auth'
import type { Refund, RefundDetail } from '../src/api/refund'
import { getRefund, listRefunds } from '../src/api/refund'
import { useAuthStore } from '../src/stores/auth'
import RefundsView from '../src/views/RefundsView.vue'
import RefundExecutionDrawer from '../src/views/refund/RefundExecutionDrawer.vue'

vi.mock('vue-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('vue-router')>()),
  useRoute: () => ({ query: {} }),
}))

vi.mock('../src/api/refund', () => ({
  listRefunds: vi.fn(),
  getRefund: vi.fn(),
  createRefund: vi.fn(),
  reviewRefund: vi.fn(),
  executeRefund: vi.fn(),
  confirmRefundSuccess: vi.fn(),
}))

vi.mock('../src/api/trading', () => ({ getOrder: vi.fn() }))

function makeUser(userId: number, permissions: string[]): AuthUser {
  return { userId, username: 'refund-test', displayName: '退款测试', userType: 'EMPLOYEE', roles: ['ADMIN'], permissions }
}

const refund: Refund = {
  id: 81,
  tenantId: 1,
  refundNo: 'RF202610260001',
  orderId: 71,
  customerId: 42,
  requestedFen: 12000,
  approvedFen: 0,
  succeededFen: 0,
  reasonCode: 'CUSTOMER_REQUEST',
  reasonDetail: '客户申请退款',
  status: 'REQUESTED',
  requestedBy: 9,
  approvedBy: null,
  executedBy: null,
  succeededAt: null,
  createdAt: '2026-10-26T01:00:00Z',
  updatedAt: '2026-10-26T01:00:00Z',
  version: 0,
}

const detail: RefundDetail = {
  refund,
  items: [{ id: 1, tenantId: 1, refundId: 81, entitlementId: 91, revokeCount: 2, allocatedFen: 12000 }],
  payments: [],
  history: [{ id: 1, tenantId: 1, refundId: 81, fromStatus: 'NONE', toStatus: 'REQUESTED', actorUserId: 9, reason: '客户申请退款', createdAt: '2026-10-26T01:00:00Z' }],
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
  const wrapper = mount(RefundsView, { global: { plugins: [ElementPlus, pinia] }, attachTo: document.body })
  wrappers.push(wrapper)
  return wrapper
}

describe('RefundsView 退款状态与操作权限', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(listRefunds).mockResolvedValue({ items: [refund], total: 1, page: 1, size: 20 })
    vi.mocked(getRefund).mockResolvedValue(detail)
  })

  it('展示中文申请状态、整数分金额和权益分摊，不伪装成功', async () => {
    const wrapper = mountView(makeUser(10, ['refund:read']))
    await flushPromises()

    expect(wrapper.text()).toContain('待审批')
    expect(wrapper.text()).toContain('120.00')
    expect(wrapper.text()).toContain('审批或执行中不代表已退款')

    await wrapper.get('[data-testid="refund-detail"]').trigger('click')
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 40))
    expect(document.body.textContent).toContain('当前不是退款成功事实')
    expect(document.body.textContent).toContain('客户主动申请')
    expect(document.body.textContent).not.toContain('CUSTOMER_REQUEST')
    expect(document.body.textContent).toContain('数据版本')
    expect(document.body.textContent).toContain('权益 #91')
    expect(document.body.textContent).toContain('12000 分')
    expect(document.body.textContent).toContain('未创建 → 待审批')
  })

  it('未知退款枚举显示中文兜底，不回显英文代码', async () => {
    const unknownRefund: Refund = {
      ...refund,
      status: 'FUTURE_STATUS' as Refund['status'],
      reasonCode: 'FUTURE_REASON',
    }
    const unknownDetail: RefundDetail = {
      ...detail,
      refund: unknownRefund,
      payments: [{
        id: 2,
        tenantId: 1,
        refundId: 81,
        provider: 'FUTURE_PROVIDER',
        externalRefundNo: 'EXT-1',
        amountFen: 12000,
        status: 'FUTURE_PAYMENT_STATUS' as RefundDetail['payments'][number]['status'],
        verificationMethod: 'FUTURE_METHOD' as RefundDetail['payments'][number]['verificationMethod'],
        evidenceRef: null,
        voucherFileId: null,
        succeededAt: null,
        createdAt: '2026-10-26T01:00:00Z',
        updatedAt: '2026-10-26T01:00:00Z',
        version: 0,
      }],
      history: [{ ...detail.history[0]!, fromStatus: 'FUTURE_STATUS', toStatus: 'FUTURE_STATUS' as Refund['status'] }],
    }
    vi.mocked(listRefunds).mockResolvedValue({ items: [unknownRefund], total: 1, page: 1, size: 20 })
    vi.mocked(getRefund).mockResolvedValue(unknownDetail)

    const wrapper = mountView(makeUser(10, ['refund:read']))
    await flushPromises()
    expect(wrapper.text()).toContain('未知退款状态')
    expect(wrapper.text()).not.toContain('FUTURE_STATUS')

    await wrapper.get('[data-testid="refund-detail"]').trigger('click')
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 40))
    expect(document.body.textContent).toContain('未知退款原因')
    expect(document.body.textContent).toContain('未知退款通道')
    expect(document.body.textContent).toContain('未知执行状态')
    expect(document.body.textContent).toContain('未知确认方式')
    expect(document.body.textContent).not.toContain('FUTURE_REASON')
    expect(document.body.textContent).not.toContain('FUTURE_PROVIDER')
    expect(document.body.textContent).not.toContain('FUTURE_PAYMENT_STATUS')
    expect(document.body.textContent).not.toContain('FUTURE_METHOD')
  })

  it('执行抽屉使用模拟、扫描通过和本地时间等中文文案', async () => {
    const executableDetail: RefundDetail = {
      ...detail,
      refund: { ...refund, status: 'APPROVED', approvedFen: 12000 },
    }
    const wrapper = mount(RefundExecutionDrawer, {
      props: { modelValue: true, mode: 'execute', detail: executableDetail },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
    wrappers.push(wrapper)
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 40))

    expect(document.body.textContent).toContain('模拟验签通道（仅本地测试）')
    expect(document.body.textContent).not.toContain('Mock')

    await wrapper.setProps({
      mode: 'success',
      detail: {
        ...executableDetail,
        refund: { ...executableDetail.refund, status: 'PROCESSING' },
        payments: [{
          id: 2,
          tenantId: 1,
          refundId: 81,
          provider: 'MOCK',
          externalRefundNo: 'EXT-1',
          amountFen: 12000,
          status: 'PENDING',
          verificationMethod: 'MANUAL_REVIEW',
          evidenceRef: null,
          voucherFileId: null,
          succeededAt: null,
          createdAt: '2026-10-26T01:00:00Z',
          updatedAt: '2026-10-26T01:00:00Z',
          version: 0,
        }],
      },
    })
    await flushPromises()
    expect(document.body.textContent).toContain('实际成功时间（本地时间）')
    expect(document.body.textContent).toContain('退款成功凭证（须扫描通过）')
    expect(document.body.textContent).not.toContain('CLEAN')
  })

  it('申请人不能自审，异人财务可以审批', async () => {
    const own = mountView(makeUser(9, ['refund:read', 'refund:approve']))
    await flushPromises()
    expect(own.find('[data-testid="refund-review"]').exists()).toBe(false)
    expect(own.text()).toContain('需异人审批')
    own.unmount()

    const other = mountView(makeUser(10, ['refund:read', 'refund:approve']))
    await flushPromises()
    expect(other.find('[data-testid="refund-review"]').exists()).toBe(true)
  })

  it('只有 refund:write 显示申请入口，读取权限不隐式开放写操作', async () => {
    const readOnly = mountView(makeUser(10, ['refund:read']))
    await flushPromises()
    expect(readOnly.find('[data-testid="refund-create"]').exists()).toBe(false)
    readOnly.unmount()

    const writer = mountView(makeUser(10, ['refund:read', 'refund:write']))
    await flushPromises()
    expect(writer.find('[data-testid="refund-create"]').exists()).toBe(true)
  })
})
