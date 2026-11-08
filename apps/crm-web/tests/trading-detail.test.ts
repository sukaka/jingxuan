import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { CrmOrder, EntitlementDetail, OrderFulfillmentView } from '../src/api/trading'
import { listRefunds } from '../src/api/refund'
import EntitlementDetailDrawer from '../src/views/trading/EntitlementDetailDrawer.vue'
import OrderDetailDrawer from '../src/views/trading/OrderDetailDrawer.vue'

vi.mock('../src/api/refund', () => ({ listRefunds: vi.fn() }))

const wrappers: VueWrapper[] = []
afterEach(() => {
  while (wrappers.length) wrappers.pop()?.unmount()
  document.body.innerHTML = ''
})

function makeOrder(status: CrmOrder['status']): CrmOrder {
  return {
    id: 71,
    orderNo: 'J000000000071',
    customerId: 42,
    customerName: '测试客户',
    soldByUserId: 9,
    ownerAtSaleId: 9,
    externalOrderNo: null,
    sourceSystem: 'CRM',
    orderTypeCode: 'REGULAR',
    campaignId: null,
    campaignBatchId: null,
    status,
    receiverType: 'PLATFORM',
    listAmountFen: 19900,
    discountAmountFen: 0,
    payableFen: 19900,
    paidFen: status === 'PAID' ? 19900 : 0,
    refundedFen: 0,
    orderedAt: '2026-10-24T01:00:00Z',
    paidAt: status === 'PAID' ? '2026-10-24T01:02:03Z' : null,
    createdAt: '2026-10-24T01:00:00Z',
    version: 2,
  }
}

function makeView(status: CrmOrder['status'], allocationStatus = 'CONFIGURED'): OrderFulfillmentView {
  const paid = status === 'PAID'
  return {
    order: {
      order: makeOrder(status),
      items: [{
        id: 711,
        orderId: 71,
        skuId: 301,
        packageVersionId: 201,
        skuCodeSnapshot: 'SKU-TEST',
        skuNameSnapshot: '测试套餐｜测试规格',
        purchaseQuantity: 1,
        listAmountFen: 19900,
        paidAllocatedFen: allocationStatus === 'PENDING_Q02' ? 0 : 19900,
        entitlementSnapshot: JSON.stringify({ allocationStatus }),
        version: 0,
      }],
      payments: paid ? [{
        id: 7111,
        orderId: 71,
        paymentChannel: 'BANK_TRANSFER',
        receiverType: 'PLATFORM',
        externalTradeNo: 'BANK-TEST-001',
        amountFen: 19900,
        status: 'CONFIRMED',
        confirmedAt: '2026-10-24T01:03:00Z',
        confirmedBy: 9,
        voucherFileId: null,
        evidenceRef: 'EVIDENCE-TEST-001',
        verificationMethod: 'MANUAL_REVIEW',
        createdAt: '2026-10-24T01:03:00Z',
        version: 0,
      }] : [],
      history: [{
        id: 7121,
        orderId: 71,
        fromStatus: 'NONE',
        toStatus: 'DRAFT',
        actorUserId: 9,
        triggerType: 'CREATE',
        triggerRef: null,
        reason: null,
        createdAt: '2026-10-24T01:00:00Z',
      }],
    },
    entitlements: paid ? [{
      id: 81,
      customerId: 42,
      orderItemId: 711,
      projectId: 101,
      projectName: '测试项目',
      grantedCount: 10,
      availableCount: 10,
      versionNo: 1,
      validFrom: '2026-10-24',
      validTo: '2027-10-24',
      status: 'AVAILABLE',
      createdAt: '2026-10-24T01:03:00Z',
      version: 0,
    }] : [],
  }
}

async function settleDrawer() {
  await flushPromises()
  await new Promise((resolve) => setTimeout(resolve, 40))
}

describe('OrderDetailDrawer 订单事实与快照', () => {
  it('草稿明确无权益、无支付流水，不能伪装成已支付', async () => {
    const wrapper = mount(OrderDetailDrawer, {
      props: { modelValue: true, view: makeView('DRAFT'), canWrite: true, canConfirmPayment: true },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
    wrappers.push(wrapper)
    await settleDrawer()

    expect(document.body.textContent).toContain('当前为草稿：未确认支付，不发放任何可用权益')
    expect(document.body.textContent).toContain('草稿不发放权益')
    expect(document.body.textContent).toContain('尚无支付确认流水')
    expect(document.body.textContent).not.toContain('人工凭证复核')
  })

  it('Q02 快照显示待分摊，不把 0 分展示为已确认分摊', async () => {
    const wrapper = mount(OrderDetailDrawer, {
      props: { modelValue: true, view: makeView('PAID', 'PENDING_Q02'), canWrite: false, canConfirmPayment: false },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
    wrappers.push(wrapper)
    await settleDrawer()

    expect(document.body.textContent).toContain('Q02 未确认：多项目实付金额待分摊')
    expect(document.body.querySelector('.allocation-value')?.textContent).toBe('待分摊')
    expect(document.body.querySelector('.allocation-value')?.textContent).not.toBe('0.00')
  })

  it('财务没有 order:write 也能从详情进入人工凭证确认', async () => {
    const wrapper = mount(OrderDetailDrawer, {
      props: { modelValue: true, view: makeView('PENDING_PAYMENT'), canWrite: false, canConfirmPayment: true },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
    wrappers.push(wrapper)
    await settleDrawer()

    const buttons = Array.from(document.body.querySelectorAll('button')).map((button) => button.textContent?.trim())
    expect(buttons).toContain('核验支付凭证')
    expect(buttons).not.toContain('提交待支付')
    expect(buttons).not.toContain('取消订单')
  })

  it('已支付详情展示人工凭证与只追加状态历史', async () => {
    const wrapper = mount(OrderDetailDrawer, {
      props: { modelValue: true, view: makeView('PAID'), canWrite: false, canConfirmPayment: false },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
    wrappers.push(wrapper)
    await settleDrawer()

    expect(document.body.textContent).toContain('BANK-TEST-001')
    expect(document.body.textContent).toContain('EVIDENCE-TEST-001')
    expect(document.body.textContent).toContain('人工凭证复核')
    expect(document.body.textContent).toContain('未创建 → 草稿')
    expect(document.body.textContent).toContain('支付事实已冻结')
  })

  it('已支付订单展示真实退款记录并提供独立退款入口', async () => {
    vi.mocked(listRefunds).mockResolvedValue({
      items: [{
        id: 81, tenantId: 1, refundNo: 'RF202610260001', orderId: 71, customerId: 42,
        requestedFen: 5000, approvedFen: 5000, succeededFen: 0, reasonCode: 'CUSTOMER_REQUEST',
        reasonDetail: '客户申请退款', status: 'PROCESSING', requestedBy: 9, approvedBy: 10,
        executedBy: 10, succeededAt: null, createdAt: '2026-10-26T01:00:00Z',
        updatedAt: '2026-10-26T01:00:00Z', version: 2,
      }],
      total: 1, page: 1, size: 100,
    })
    const wrapper = mount(OrderDetailDrawer, {
      props: { modelValue: true, view: makeView('PAID'), canWrite: false, canConfirmPayment: false, canViewRefunds: true, canRequestRefund: true },
      global: { plugins: [ElementPlus], stubs: { RouterLink: { template: '<a><slot /></a>' } } },
      attachTo: document.body,
    })
    wrappers.push(wrapper)
    await settleDrawer()

    expect(listRefunds).toHaveBeenCalledWith({ page: 1, size: 100, orderId: 71 })
    expect(document.body.textContent).toContain('RF202610260001')
    expect(document.body.textContent).toContain('退款执行中')
    expect(document.body.textContent).toContain('申请、审批和执行中均不计入已退款')
    const refundButton = document.body.querySelector('[data-testid="order-request-refund"]')
    expect(refundButton).not.toBeNull()
  })
})

describe('EntitlementDetailDrawer 权威权益流水', () => {
  it('展示 GRANT 正流水、幂等来源与可用余额，不提供管家核销按钮', async () => {
    const detail: EntitlementDetail = {
      entitlement: makeView('PAID').entitlements[0]!,
      availableBalance: 10,
      ledger: [{
        id: 9001,
        entitlementId: 81,
        txnType: 'GRANT',
        deltaCount: 10,
        sourceType: 'PAYMENT',
        sourceRef: '7111',
        idempotencyKey: 'entitlement.grant:71:711:101',
        actorUserId: 9,
        reason: '支付确认发放',
        createdAt: '2026-10-24T01:03:00Z',
        version: 0,
      }],
    }
    const wrapper = mount(EntitlementDetailDrawer, {
      props: { modelValue: true, detail },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
    wrappers.push(wrapper)
    await settleDrawer()

    expect(document.body.textContent).toContain('权威流水汇总可用次数')
    expect(document.body.textContent).toContain('权益发放')
    expect(document.body.textContent).toContain('+10')
    expect(document.body.textContent).toContain('entitlement.grant:71:711:101')
    expect(document.body.textContent).toContain('当前不开放管家正式核销')
    const buttons = Array.from(document.body.querySelectorAll('button')).map((button) => button.textContent?.trim())
    expect(buttons).not.toContain('正式核销')
  })
})
