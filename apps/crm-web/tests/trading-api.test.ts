import type { AxiosAdapter } from 'axios'
import { afterEach, describe, expect, it } from 'vitest'
import {
  cancelOrder,
  confirmPayment,
  createOrder,
  getEntitlement,
  getOrder,
  listEntitlements,
  listOrders,
  submitOrder,
} from '../src/api/trading'
import { http } from '../src/api/http'

const originalAdapter = http.defaults.adapter
afterEach(() => { http.defaults.adapter = originalAdapter })

function capture(response: unknown) {
  const calls: { method: string; url: string; data: unknown; params: unknown; idempotencyKey: unknown }[] = []
  const adapter: AxiosAdapter = async (config) => {
    calls.push({
      method: config.method ?? 'get',
      url: config.url ?? '',
      data: typeof config.data === 'string' ? JSON.parse(config.data) : config.data,
      params: config.params,
      idempotencyKey: config.headers?.get('Idempotency-Key'),
    })
    return {
      status: 200,
      statusText: 'OK',
      headers: {},
      config,
      data: { code: 'OK', message: 'success', data: response, requestId: 'request-test', timestamp: '' },
    }
  }
  return { adapter, calls }
}

describe('P0-3 交易 API 契约', () => {
  it('订单和权益查询保留客户、状态与后端分页参数', async () => {
    const { adapter, calls } = capture({ items: [], total: 0, page: 2, size: 20 })
    http.defaults.adapter = adapter

    await listOrders({ page: 3, size: 50, customerId: 42, status: 'PENDING_PAYMENT' })
    await listEntitlements({ page: 2, size: 20, customerId: 42, status: 'AVAILABLE' })
    await getOrder(71)
    await getEntitlement(81)

    expect(calls.map((call) => call.url)).toEqual(['/orders', '/entitlements', '/orders/71', '/entitlements/81'])
    expect(calls[0]?.params).toEqual({ page: 3, size: 50, customerId: 42, status: 'PENDING_PAYMENT' })
    expect(calls[1]?.params).toEqual({ page: 2, size: 20, customerId: 42, status: 'AVAILABLE' })
  })

  it('创建草稿不虚构活动 ID，金额直接传整数分', async () => {
    const { adapter, calls } = capture({})
    http.defaults.adapter = adapter

    await createOrder({
      customerId: 42,
      receiverType: 'PLATFORM',
      orderTypeCode: 'REGULAR',
      payableFen: 19900,
      items: [{ skuId: 301, quantity: 2 }],
    })

    expect(calls[0]?.method).toBe('post')
    expect(calls[0]?.url).toBe('/orders')
    expect(calls[0]?.data).toMatchObject({ customerId: 42, payableFen: 19900, items: [{ skuId: 301, quantity: 2 }] })
    expect(calls[0]?.data).not.toHaveProperty('campaignId')
    expect(calls[0]?.data).not.toHaveProperty('campaignBatchId')
  })

  it('提交与取消携带乐观锁版本和审计原因', async () => {
    const { adapter, calls } = capture({})
    http.defaults.adapter = adapter

    await submitOrder(71, 4, '人工提交待支付')
    await cancelOrder(71, 5, '客户取消测试订单')

    expect(calls[0]?.url).toBe('/orders/71/submit')
    expect(calls[0]?.data).toEqual({ version: 4, reason: '人工提交待支付' })
    expect(calls[1]?.url).toBe('/orders/71/cancel')
    expect(calls[1]?.data).toEqual({ version: 5, reason: '客户取消测试订单' })
  })

  it('人工支付确认使用真实凭证、全额整数分和独立幂等头', async () => {
    const { adapter, calls } = capture({})
    http.defaults.adapter = adapter

    await confirmPayment(71, {
      externalTradeNo: 'BANK-TEST-001',
      paymentChannel: 'BANK_TRANSFER',
      amountFen: 19900,
      paidAt: '2026-10-24T01:02:03.000Z',
      evidenceRef: 'EVIDENCE-TEST-001',
    }, 'payment-idem-test-71')

    expect(calls[0]?.url).toBe('/orders/71/payment-confirmations')
    expect(calls[0]?.data).toEqual({
      externalTradeNo: 'BANK-TEST-001',
      paymentChannel: 'BANK_TRANSFER',
      amountFen: 19900,
      paidAt: '2026-10-24T01:02:03.000Z',
      evidenceRef: 'EVIDENCE-TEST-001',
    })
    expect(calls[0]?.idempotencyKey).toBe('payment-idem-test-71')
  })
})
