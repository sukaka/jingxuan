import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { CrmOrder, OrderFulfillmentView } from '../src/api/trading'
import { confirmPayment } from '../src/api/trading'
import { ApiError } from '../src/types/api'
import PaymentConfirmDrawer from '../src/views/trading/PaymentConfirmDrawer.vue'

vi.mock('../src/api/trading', () => ({
  confirmPayment: vi.fn(),
}))

vi.mock('../src/views/trading/tradingUi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../src/views/trading/tradingUi')>()
  return { ...actual, createPaymentIdempotencyKey: vi.fn(() => 'payment-idem-fixed') }
})

const order: CrmOrder = {
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
  status: 'PENDING_PAYMENT',
  receiverType: 'PLATFORM',
  listAmountFen: 19900,
  discountAmountFen: 0,
  payableFen: 19900,
  paidFen: 0,
  refundedFen: 0,
  orderedAt: '2026-10-24T01:00:00Z',
  paidAt: null,
  createdAt: '2026-10-24T01:00:00Z',
  version: 2,
}

const savedView: OrderFulfillmentView = {
  order: {
    order: { ...order, status: 'PAID', paidFen: 19900, version: 3 },
    items: [],
    payments: [],
    history: [],
  },
  entitlements: [],
}

const wrappers: VueWrapper[] = []
afterEach(() => {
  while (wrappers.length) wrappers.pop()?.unmount()
  document.body.innerHTML = ''
  vi.clearAllMocks()
})

function nativeInputSelector(testId: string): string {
  return `input[data-testid="${testId}"], [data-testid="${testId}"] input`
}

async function setInput(testId: string, value: string) {
  const input = document.body.querySelector<HTMLInputElement>(nativeInputSelector(testId))
  expect(input).not.toBeNull()
  input!.value = value
  input!.dispatchEvent(new Event('input', { bubbles: true }))
  await flushPromises()
}

async function clickSubmit() {
  const button = document.body.querySelector<HTMLButtonElement>('[data-testid="payment-submit"]')
  expect(button).not.toBeNull()
  button!.click()
  await flushPromises()
}

describe('PaymentConfirmDrawer 人工可信支付确认', () => {
  it('失败重试沿用同一幂等键，请求始终使用订单应付整数分和真实凭证', async () => {
    vi.mocked(confirmPayment)
      .mockRejectedValueOnce(new ApiError({ status: 0, code: 'NETWORK_ERROR', message: '测试网络失败' }))
      .mockResolvedValueOnce(savedView)
    const wrapper = mount(PaymentConfirmDrawer, {
      props: { modelValue: false, order },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
    wrappers.push(wrapper)
    await wrapper.setProps({ modelValue: true })
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 40))

    wrapper.getComponent({ name: 'ElDatePicker' }).vm.$emit('update:modelValue', new Date('2026-10-24T01:02:03.000Z'))
    await setInput('payment-trade-no', 'BANK-TEST-001')
    await setInput('payment-evidence', 'EVIDENCE-TEST-001')
    const verified = document.body.querySelector<HTMLInputElement>(nativeInputSelector('payment-verified'))
    expect(verified).not.toBeNull()
    verified!.checked = true
    verified!.dispatchEvent(new Event('change', { bubbles: true }))
    await flushPromises()

    await clickSubmit()
    await clickSubmit()

    expect(confirmPayment).toHaveBeenCalledTimes(2)
    expect(confirmPayment).toHaveBeenNthCalledWith(1, 71, expect.objectContaining({
      externalTradeNo: 'BANK-TEST-001',
      paymentChannel: 'BANK_TRANSFER',
      amountFen: 19900,
      evidenceRef: 'EVIDENCE-TEST-001',
    }), 'payment-idem-fixed')
    expect(confirmPayment).toHaveBeenNthCalledWith(2, 71, expect.objectContaining({
      externalTradeNo: 'BANK-TEST-001',
      amountFen: 19900,
    }), 'payment-idem-fixed')
    expect(document.body.textContent).toContain('不是有赞或支付网关回调')
    expect(document.body.textContent).toContain('提交时会保留支付时间的时区信息')
    expect(document.body.textContent).not.toContain('ISO 8601')
  })
})
