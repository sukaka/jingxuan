import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuthUser } from '../src/api/auth'
import type { EntitlementCorrectionView, Redemption, RedemptionDetail, RedemptionReversal, ReversalDetail } from '../src/api/fulfillment'
import {
  approveRedemptionReversal,
  getRedemption,
  getRedemptionReversal,
  listRedemptionReversals,
  listRedemptions,
  requestRedemptionReversal,
} from '../src/api/fulfillment'
import type { EntitlementDetail } from '../src/api/trading'
import { getEntitlement } from '../src/api/trading'
import { useAuthStore } from '../src/stores/auth'
import { ApiError } from '../src/types/api'
import RedemptionsView from '../src/views/RedemptionsView.vue'

vi.mock('../src/api/fulfillment', () => ({
  listRedemptions: vi.fn(),
  getRedemption: vi.fn(),
  requestRedemptionReversal: vi.fn(),
  listRedemptionReversals: vi.fn(),
  getRedemptionReversal: vi.fn(),
  approveRedemptionReversal: vi.fn(),
  rejectRedemptionReversal: vi.fn(),
}))
vi.mock('../src/api/trading', () => ({ getEntitlement: vi.fn() }))
vi.mock('../src/views/fulfillment/RedemptionStats.vue', () => ({
  default: { name: 'RedemptionStats', template: '<div data-testid="redemption-stats-stub" />' },
}))

const posted: Redemption = {
  id: 701,
  appointmentId: 501,
  customerId: 42,
  institutionId: 91,
  ownerStaffId: 9,
  teamId: 1,
  serviceAt: '2026-10-25T02:00:00Z',
  evidenceRef: 'SERVICE-EVIDENCE-TEST',
  voucherFileId: null,
  status: 'POSTED',
  settlementStatus: 'PENDING_VERIFICATION',
  idempotencyKey: 'redemption-test-key',
  postedBy: 88,
  postedAt: '2026-10-25T03:00:00Z',
  createdAt: '2026-10-25T03:00:00Z',
  version: 0,
}

const postedDetail: RedemptionDetail = {
  redemption: posted,
  lines: [{ id: 702, redemptionId: 701, appointmentItemId: 601, entitlementId: 81, projectId: 101, count: 2, createdAt: '2026-10-25T03:00:00Z' }],
  history: [],
}

const entitlementDetail: EntitlementDetail = {
  entitlement: {
    id: 81,
    customerId: 42,
    orderItemId: 711,
    projectId: 101,
    projectName: '测试项目',
    grantedCount: 10,
    availableCount: 6,
    versionNo: 1,
    validFrom: '2026-10-24',
    validTo: '2027-10-24',
    status: 'AVAILABLE',
    createdAt: '2026-10-24T01:03:00Z',
    version: 0,
  },
  availableBalance: 6,
  ledger: [
    { id: 9001, entitlementId: 81, txnType: 'GRANT', deltaCount: 10, sourceType: 'PAYMENT', sourceRef: '7111', idempotencyKey: 'entitlement.grant:71:711:101', actorUserId: 9, reason: '支付确认发放', createdAt: '2026-10-24T01:03:00Z', version: 0 },
    { id: 9002, entitlementId: 81, txnType: 'REDEEM', deltaCount: -2, sourceType: 'REDEMPTION', sourceRef: '700', idempotencyKey: 'redemption.post:700:81', actorUserId: 88, reason: '历史正式核销', createdAt: '2026-10-24T03:00:00Z', version: 0 },
    { id: 9003, entitlementId: 81, txnType: 'REDEEM', deltaCount: -2, sourceType: 'REDEMPTION', sourceRef: '701', idempotencyKey: 'redemption.post:701:81', actorUserId: 88, reason: '本次正式核销', createdAt: '2026-10-25T03:00:00Z', version: 0 },
  ],
}

const requestedReversal: RedemptionReversal = {
  id: 801,
  redemptionId: 701,
  customerId: 42,
  institutionId: 91,
  ownerStaffId: 9,
  teamId: 1,
  status: 'REQUESTED',
  reason: '履约异常测试',
  requestedBy: 9,
  requestedAt: '2026-10-25T04:00:00Z',
  reviewedBy: null,
  reviewedAt: null,
  reviewReason: null,
  requestIdempotencyKey: 'reversal-request-fixed',
  reviewIdempotencyKey: null,
  activeRedemptionId: 701,
  createdAt: '2026-10-25T04:00:00Z',
  version: 1,
}

const approvedRequested: RedemptionReversal = {
  ...requestedReversal,
  status: 'APPROVED',
  reviewedBy: 1,
  reviewedAt: '2026-10-25T05:00:00Z',
  reviewReason: '人工审核通过，按补偿流水处理',
  reviewIdempotencyKey: 'reversal-review-fixed',
  version: 2,
}

const approvedReversal: RedemptionReversal = {
  ...approvedRequested,
  id: 802,
}

function correction(id: number): EntitlementCorrectionView {
  return {
    ledgerId: id,
    entitlementId: 81,
    projectId: 101,
    txnType: 'CORRECTION_IN',
    deltaCount: 2,
    sourceRef: '801:702',
    idempotencyKey: 'redemption.reversal:801:702',
    actorUserId: 1,
    reason: '真实冲正补偿测试',
    createdAt: '2026-10-25T05:00:00Z',
  }
}

function reversalDetail(reversal: RedemptionReversal, corrections = [correction(9001)]): ReversalDetail {
  return {
    reversal,
    history: [{ id: 803, reversalId: reversal.id, fromStatus: 'REQUESTED', toStatus: reversal.status, actorUserId: 1, reason: reversal.reviewReason, createdAt: '2026-10-25T05:00:00Z' }],
    corrections,
  }
}

function makeUser(permissions: string[]): AuthUser {
  return {
    userId: 1,
    username: 'redemption-review-test',
    displayName: '核销审核测试',
    userType: 'EMPLOYEE',
    roles: ['ADMIN'],
    permissions,
  }
}

const wrappers: VueWrapper[] = []
afterEach(() => {
  while (wrappers.length) wrappers.pop()?.unmount()
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})

function mountView(permissions: string[]) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = makeUser(permissions)
  auth.initialized = true
  const wrapper = mount(RedemptionsView, {
    global: { plugins: [ElementPlus, pinia] },
    attachTo: document.body,
  })
  wrappers.push(wrapper)
  return wrapper
}

async function openReversals(wrapper: VueWrapper) {
  const tab = wrapper.findAll('.el-tabs__item').find((item) => item.text() === '冲正申请与审核')
  expect(tab).toBeDefined()
  await tab?.trigger('click')
  await flushPromises()
}

describe('RedemptionsView 原核销与冲正审核', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(listRedemptions).mockResolvedValue({ items: [posted], total: 1, page: 1, size: 20 })
    vi.mocked(getRedemption).mockResolvedValue(postedDetail)
    vi.mocked(getEntitlement).mockResolvedValue(entitlementDetail)
    vi.mocked(listRedemptionReversals).mockResolvedValue({ items: [requestedReversal, approvedReversal], total: 2, page: 1, size: 20 })
    vi.mocked(getRedemptionReversal).mockResolvedValue(reversalDetail(approvedReversal))
    vi.mocked(requestRedemptionReversal).mockResolvedValue(reversalDetail(requestedReversal, []))
  })

  it('摘要只读且金额待核实，只有详情返回真实 corrections 才显示补偿生效', async () => {
    const wrapper = mountView(['redemption:read', 'redemption:reverse', 'redemption:approve'])
    await flushPromises()

    expect(getRedemption).toHaveBeenCalledWith(701)
    expect(getEntitlement).toHaveBeenCalledWith(81)
    const table = wrapper.get('.proto-table-wrap')
    expect(table.text()).toContain('项目 #101')
    expect(table.text()).toContain('机构 #91')
    expect(table.text()).toContain('累计已核销')
    expect(table.text()).toContain('4')
    expect(wrapper.findAll('button').map((button) => button.text())).toContain('申请冲正')

    const detailButton = wrapper.findAll('button').find((button) => button.text() === '只读详情')
    expect(detailButton).toBeDefined()
    await detailButton!.trigger('click')
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 30))
    expect(document.body.textContent).toContain('机构正式核销')
    expect(document.body.textContent).toContain('不可直接编辑的原始核销事实')
    expect(document.body.textContent).toContain('待核实')
    expect(document.body.textContent).not.toContain('0.00')

    await openReversals(wrapper)
    expect(wrapper.text()).toContain('仅为冲正申请，未改余额')
    expect(wrapper.text()).toContain('审核通过，未返回补偿流水，需核对')
    expect(wrapper.text()).not.toContain('补偿已生效')

    await wrapper.findAll('[data-testid="reversal-detail"]')[1]!.trigger('click')
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 30))
    expect(getRedemptionReversal).toHaveBeenCalledWith(802)
    expect(document.body.textContent).toContain('补偿已生效 · 1 笔 / +2 次')
    expect(document.body.textContent).toContain('补偿增加')
    expect(document.body.textContent).toContain('待审核 → 审核通过')
    expect(document.body.textContent).not.toContain('CORRECTION_IN')
  })

  it('审核失败重试固定同一幂等键，真实补偿返回后才显示 CORRECTION_IN', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    vi.mocked(listRedemptionReversals)
      .mockResolvedValueOnce({ items: [requestedReversal], total: 1, page: 1, size: 20 })
      .mockResolvedValueOnce({ items: [approvedRequested], total: 1, page: 1, size: 20 })
    vi.mocked(approveRedemptionReversal)
      .mockRejectedValueOnce(new ApiError({ status: 0, code: 'NETWORK_ERROR', message: '审核网络失败测试' }))
      .mockResolvedValueOnce(reversalDetail(approvedRequested))
    const wrapper = mountView(['redemption:read', 'redemption:approve'])
    await flushPromises()
    await openReversals(wrapper)

    await wrapper.get('[data-testid="reversal-approve"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="reversal-approve"]').trigger('click')
    await flushPromises()

    expect(approveRedemptionReversal).toHaveBeenCalledTimes(2)
    const firstKey = vi.mocked(approveRedemptionReversal).mock.calls[0]?.[2]
    const secondKey = vi.mocked(approveRedemptionReversal).mock.calls[1]?.[2]
    expect(firstKey).toBeTruthy()
    expect(secondKey).toBe(firstKey)
    expect(approveRedemptionReversal).toHaveBeenNthCalledWith(1, 801, { version: 1, reason: '人工审核通过，按补偿流水处理' }, firstKey)
    expect(wrapper.text()).toContain('补偿已生效 · 1 笔 / +2 次')
  })

  it('APPROVED 但 corrections 为空时明确需核对，不伪造加权益成功', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    vi.mocked(listRedemptionReversals)
      .mockResolvedValueOnce({ items: [requestedReversal], total: 1, page: 1, size: 20 })
      .mockResolvedValueOnce({ items: [approvedRequested], total: 1, page: 1, size: 20 })
    vi.mocked(approveRedemptionReversal).mockResolvedValue(reversalDetail(approvedRequested, []))
    const wrapper = mountView(['redemption:read', 'redemption:approve'])
    await flushPromises()
    await openReversals(wrapper)

    await wrapper.get('[data-testid="reversal-approve"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('审核通过，未返回补偿流水，需核对')
    expect(wrapper.text()).not.toContain('补偿已生效')
  })

  it('redemption:reverse 先读取完整事实再申请，携带版本且无审核按钮', async () => {
    const wrapper = mountView(['redemption:read', 'redemption:reverse'])
    await flushPromises()
    const reverseButton = wrapper.findAll('button').find((button) => button.text() === '申请冲正')
    expect(reverseButton).toBeDefined()
    vi.mocked(getRedemption).mockClear()
    await reverseButton!.trigger('click')
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 30))

    expect(getRedemption).toHaveBeenCalledWith(701)
    expect(document.body.textContent).toContain('事实版本 0')
    const reason = document.body.querySelector<HTMLTextAreaElement>('textarea[data-testid="reversal-reason"], [data-testid="reversal-reason"] textarea')
    expect(reason).not.toBeNull()
    reason!.value = '真实履约异常申请'
    reason!.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()
    await wrapper.get('[data-testid="reversal-request-submit"]').trigger('click')
    await flushPromises()

    expect(requestRedemptionReversal).toHaveBeenCalledWith(701, { redemptionVersion: 0, reason: '真实履约异常申请' }, expect.any(String))
    await openReversals(wrapper)
    expect(wrapper.find('[data-testid="reversal-approve"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="reversal-reject"]').exists()).toBe(false)
  })
})
