import type { AxiosAdapter } from 'axios'
import { afterEach, describe, expect, it } from 'vitest'
import {
  adjustCustomerPoints,
  approveOwnershipTransfer,
  confirmMergeCase,
  createCustomer,
  createMergeCase,
  createOwnershipTransfer,
  listCustomers,
  getMergeReversal,
  listMergeCandidates,
  rejectMergeCase,
  requestMergeReversal,
  reviewMergeCase,
  reviewMergeReversal,
  updateCustomer,
  updateCustomerTags,
} from '../src/api/customer'
import { http } from '../src/api/http'

const originalAdapter = http.defaults.adapter

afterEach(() => {
  http.defaults.adapter = originalAdapter
})

interface CapturedCall {
  method: string
  url: string
  params: Record<string, unknown>
  data: unknown
  headers: Record<string, unknown>
}

/** 捕获请求并返回固定响应体的适配器 */
function captureAdapter(responseData: unknown, status = 200): { adapter: AxiosAdapter; calls: CapturedCall[] } {
  const calls: CapturedCall[] = []
  const adapter: AxiosAdapter = async (config) => {
    calls.push({
      method: (config.method ?? 'get').toLowerCase(),
      url: config.url ?? '',
      params: (config.params ?? {}) as Record<string, unknown>,
      data: config.data ? JSON.parse(config.data as string) : undefined,
      headers: config.headers as unknown as Record<string, unknown>,
    })
    return {
      status,
      statusText: 'OK',
      headers: {},
      config,
      data: { code: 'OK', message: 'success', data: responseData, requestId: 'req-t', timestamp: '' },
    }
  }
  return { adapter, calls }
}

describe('customer api 契约', () => {
  it('listCustomers 透传全部筛选参数,空串不下发', async () => {
    const { adapter, calls } = captureAdapter({ items: [], total: 0, page: 1, size: 20 })
    http.defaults.adapter = adapter

    await listCustomers({
      page: 2,
      size: 50,
      keyword: '王丽',
      status: 'ACTIVE',
      cityCode: 'HZ',
      sourceCode: '',
      channelCode: 'XHS',
      tierCode: 'S',
      ownerId: 2,
      followupDueBefore: '2026-10-25T00:00:00',
    })

    expect(calls).toHaveLength(1)
    expect(calls[0]?.method).toBe('get')
    expect(calls[0]?.url).toBe('/customers')
    expect(calls[0]?.params).toEqual({
      page: 2,
      size: 50,
      keyword: '王丽',
      status: 'ACTIVE',
      cityCode: 'HZ',
      sourceCode: undefined,
      channelCode: 'XHS',
      tierCode: 'S',
      ownerId: 2,
      followupDueBefore: '2026-10-25T00:00:00',
    })
  })

  it('createCustomer 携带 Idempotency-Key 头(防重复建档)', async () => {
    const { adapter, calls } = captureAdapter({ id: 1, customerNo: 'C0000000001' }, 201)
    http.defaults.adapter = adapter

    await createCustomer({ nickname: '小鱼' }, 'idem-key-12345')

    expect(calls[0]?.method).toBe('post')
    expect(calls[0]?.url).toBe('/customers')
    expect(calls[0]?.data).toEqual({ nickname: '小鱼' })
    expect(calls[0]?.headers['Idempotency-Key']).toBe('idem-key-12345')
  })

  it('updateCustomer PATCH 携带 version 乐观锁', async () => {
    const { adapter, calls } = captureAdapter({ id: 7 })
    http.defaults.adapter = adapter

    await updateCustomer(7, { nickname: '新昵称', version: 3 })

    expect(calls[0]?.method).toBe('patch')
    expect(calls[0]?.url).toBe('/customers/7')
    expect(calls[0]?.data).toEqual({ nickname: '新昵称', version: 3 })
  })

  it('updateCustomerTags 全量替换 PUT tagCodes', async () => {
    const { adapter, calls } = captureAdapter({ id: 7 })
    http.defaults.adapter = adapter

    await updateCustomerTags(7, ['VIP', 'HIGH_INTENT'])

    expect(calls[0]?.method).toBe('put')
    expect(calls[0]?.url).toBe('/customers/7/tags')
    expect(calls[0]?.data).toEqual({ tagCodes: ['VIP', 'HIGH_INTENT'] })
  })

  it('adjustCustomerPoints 只走流水追加端点', async () => {
    const { adapter, calls } = captureAdapter({ balance: 80, ledger: [] })
    http.defaults.adapter = adapter

    await adjustCustomerPoints(7, { delta: -20, reasonCode: 'CORRECTION', reason: '录入错误冲正' }, 'points-idem-001')

    expect(calls[0]?.method).toBe('post')
    expect(calls[0]?.url).toBe('/customers/7/points-adjustments')
    expect(calls[0]?.data).toEqual({ delta: -20, reasonCode: 'CORRECTION', reason: '录入错误冲正' })
    expect(calls[0]?.headers['Idempotency-Key']).toBe('points-idem-001')
  })
})

describe('合并状态机 api 契约', () => {
  it('listMergeCandidates 为分页接口,带状态筛选', async () => {
    const { adapter, calls } = captureAdapter({ items: [], total: 0, page: 1, size: 20 })
    http.defaults.adapter = adapter

    await listMergeCandidates({ page: 1, size: 20, status: 'SUGGESTED' })

    expect(calls[0]?.url).toBe('/customer-merge-candidates')
    expect(calls[0]?.params).toEqual({ page: 1, size: 20, status: 'SUGGESTED' })
  })

  it('createMergeCase 只登记候选(主从 ID),不直接确认合并', async () => {
    const { adapter, calls } = captureAdapter({ id: 9, status: 'SUGGESTED' })
    http.defaults.adapter = adapter

    await createMergeCase({ primaryCustomerId: 1, secondaryCustomerId: 2 })

    expect(calls[0]?.method).toBe('post')
    expect(calls[0]?.url).toBe('/customer-merges')
    expect(calls[0]?.data).toEqual({ primaryCustomerId: 1, secondaryCustomerId: 2 })
  })

  it('review/confirm/reject 分别走独立端点并携带 version', async () => {
    const { adapter, calls } = captureAdapter({ id: 9 })
    http.defaults.adapter = adapter

    await reviewMergeCase(9, 1)
    await confirmMergeCase(9, 2, '同手机号核实为同一人')
    await rejectMergeCase(10, 1, '不是同一人')

    expect(calls[0]?.url).toBe('/customer-merges/9/review')
    expect(calls[0]?.data).toEqual({ version: 1 })
    expect(calls[1]?.url).toBe('/customer-merges/9/confirm')
    expect(calls[1]?.data).toEqual({ version: 2, reason: '同手机号核实为同一人' })
    expect(calls[2]?.url).toBe('/customer-merges/10/reject')
    expect(calls[2]?.data).toEqual({ version: 1, reason: '不是同一人' })
  })

  it('更正申请、查询和审批使用独立端点及幂等键', async () => {
    const { adapter, calls } = captureAdapter({ id: 31, mergeCaseId: 9, status: 'REQUESTED', version: 0 })
    http.defaults.adapter = adapter

    await requestMergeReversal(9, '误合并', 'merge-reversal-request-key')
    await getMergeReversal(9)
    await reviewMergeReversal(31, 'approve', { version: 0, reason: '同意更正' }, 'merge-reversal-approve-key')
    await reviewMergeReversal(31, 'complete', { version: 1, reason: '人工核对完成' }, 'merge-reversal-complete-key')
    await reviewMergeReversal(31, 'reject', { version: 0, reason: '暂不更正' }, 'merge-reversal-reject-key')

    expect(calls.map((call) => call.url)).toEqual([
      '/customer-merge-candidates/9/reversal-requests',
      '/customer-merge-reversals',
      '/customer-merge-reversals/31/approve',
      '/customer-merge-reversals/31/complete',
      '/customer-merge-reversals/31/reject',
    ])
    expect(calls[0]?.data).toEqual({ reason: '误合并' })
    expect(calls[1]?.params).toEqual({ mergeCaseId: 9 })
    expect(calls[2]?.data).toEqual({ version: 0, reason: '同意更正' })
    expect(calls[3]?.data).toEqual({ version: 1, reason: '人工核对完成' })
    expect(calls[4]?.headers['Idempotency-Key']).toBe('merge-reversal-reject-key')
  })
})

describe('转交状态机 api 契约', () => {
  it('createOwnershipTransfer 挂在客户路径下,approve/reject 走独立审批端点', async () => {
    const { adapter, calls } = captureAdapter({ id: 5, status: 'PENDING' })
    http.defaults.adapter = adapter

    await createOwnershipTransfer(7, { newOwnerId: 3, reason: '离职交接' })
    await approveOwnershipTransfer(5, 0)

    expect(calls[0]?.url).toBe('/customers/7/ownership-transfers')
    expect(calls[0]?.data).toEqual({ newOwnerId: 3, reason: '离职交接' })
    expect(calls[1]?.url).toBe('/customer-ownership-transfers/5/approve')
    expect(calls[1]?.data).toEqual({ version: 0 })
  })
})
