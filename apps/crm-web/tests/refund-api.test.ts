import type { AxiosAdapter } from 'axios'
import { afterEach, describe, expect, it } from 'vitest'
import { http } from '../src/api/http'
import {
  confirmRefundSuccess,
  createRefund,
  executeRefund,
  getRefund,
  listRefunds,
  reviewRefund,
} from '../src/api/refund'

const originalAdapter = http.defaults.adapter
afterEach(() => { http.defaults.adapter = originalAdapter })

function capture() {
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
      data: { code: 'OK', message: 'success', data: {}, requestId: 'request-test', timestamp: '' },
    }
  }
  return { adapter, calls }
}

describe('P1-4 退款 API 契约', () => {
  it('查询保留后端分页与订单客户状态筛选', async () => {
    const { adapter, calls } = capture()
    http.defaults.adapter = adapter

    await listRefunds({ page: 2, size: 50, orderId: 71, customerId: 42, status: 'PROCESSING' })
    await getRefund(81)

    expect(calls.map((call) => [call.method, call.url])).toEqual([
      ['get', '/refunds'],
      ['get', '/refunds/81'],
    ])
    expect(calls[0]?.params).toEqual({ page: 2, size: 50, orderId: 71, customerId: 42, status: 'PROCESSING' })
  })

  it('申请明确整数分和每个权益的人工回收决策', async () => {
    const { adapter, calls } = capture()
    http.defaults.adapter = adapter

    await createRefund({
      orderId: 71,
      requestedFen: 12000,
      reasonCode: 'CUSTOMER_REQUEST',
      reasonDetail: '客户申请退款',
      items: [
        { entitlementId: 91, revokeCount: 2, allocatedFen: 7000 },
        { entitlementId: 92, revokeCount: 1, allocatedFen: 5000 },
      ],
    }, 'refund-create-key')

    expect(calls[0]).toMatchObject({ method: 'post', url: '/refunds', idempotencyKey: 'refund-create-key' })
    expect(calls[0]?.data).toMatchObject({ requestedFen: 12000, items: [{ allocatedFen: 7000 }, { allocatedFen: 5000 }] })
  })

  it('审批、执行、成功是三个独立请求并保留真实凭证', async () => {
    const { adapter, calls } = capture()
    http.defaults.adapter = adapter

    await reviewRefund(81, { version: 0, decision: 'APPROVED', approvedFen: 12000, reason: '财务复核通过' }, 'review-key')
    await executeRefund(81, { version: 1, provider: 'BANK_TRANSFER', externalRefundNo: 'RF-BANK-001', amountFen: 12000, evidenceRef: 'EXEC-001' }, 'execute-key')
    await confirmRefundSuccess(81, {
      externalRefundNo: 'RF-BANK-001',
      amountFen: 12000,
      succeededAt: '2026-10-26T02:03:04.000Z',
      evidenceRef: 'SUCCESS-001',
      voucherFileId: 501,
    }, 'success-key')

    expect(calls.map((call) => call.url)).toEqual([
      '/refunds/81/review',
      '/refunds/81/executions',
      '/refunds/81/success',
    ])
    expect(calls.map((call) => call.idempotencyKey)).toEqual(['review-key', 'execute-key', 'success-key'])
    expect(calls[0]?.data).toMatchObject({ decision: 'APPROVED', approvedFen: 12000 })
    expect(calls[1]?.data).toMatchObject({ amountFen: 12000 })
    expect(calls[2]?.data).toMatchObject({ amountFen: 12000, voucherFileId: 501 })
  })
})
