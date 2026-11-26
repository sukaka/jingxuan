import type { AxiosAdapter } from 'axios'
import { afterEach, describe, expect, it } from 'vitest'
import {
  adjustCustomerPoints,
  approveOwnershipTransfer,
  assignCustomerOwner,
  batchAssignCustomerOwners,
  confirmMergeCase,
  createCustomer,
  createCustomerChannel,
  createCustomerSource,
  createCustomerTag,
  createMergeCase,
  createOwnershipTransfer,
  disableCustomerChannel,
  disableCustomerSource,
  disableCustomerTag,
  enableCustomerChannel,
  enableCustomerSource,
  enableCustomerTag,
  getCustomerStewardRelations,
  getMergeReversal,
  getMultiStewardSummary,
  listCustomerChannels,
  listCustomerOwnershipLogs,
  listCustomerSources,
  listCustomers,
  listCustomerTags,
  listMergeCandidates,
  listMultiStewardCustomers,
  listScopedOwnershipTransfers,
  rejectMergeCase,
  requestMergeReversal,
  reviewMergeCase,
  reviewMergeReversal,
  updateCustomer,
  updateCustomerChannel,
  updateCustomerSource,
  updateCustomerTag,
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

  it('listCustomers 将 tagCodes 序列化为逗号分隔字符串并附带 tagMatchMode（空标签不下发）', async () => {
    const { adapter, calls } = captureAdapter({ items: [], total: 0, page: 1, size: 20 })
    http.defaults.adapter = adapter

    await listCustomers({ page: 1, size: 20, tagCodes: ['VIP', 'HIGH_INTENT'] })
    await listCustomers({ page: 1, size: 20, tagCodes: ['VIP', 'HIGH_INTENT'], tagMatchMode: 'ALL' })
    await listCustomers({ page: 1, size: 20, tagCodes: [], tagMatchMode: 'ALL' })

    expect(calls[0]?.params).toMatchObject({ tagCodes: 'VIP,HIGH_INTENT', tagMatchMode: 'ANY' })
    expect(calls[1]?.params).toMatchObject({ tagCodes: 'VIP,HIGH_INTENT', tagMatchMode: 'ALL' })
    expect(calls[2]?.params.tagCodes).toBeUndefined()
    expect(calls[2]?.params.tagMatchMode).toBeUndefined()
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

  it('客户标签库管理端点契约（GET/POST/PATCH/disable/enable /customer-tags）', async () => {
    const { adapter, calls } = captureAdapter([])
    http.defaults.adapter = adapter

    await listCustomerTags()
    await createCustomerTag({ optionLabel: '敏感肌', sortNo: 6 })
    await updateCustomerTag(9, { optionLabel: '极敏感肌', sortNo: 7, version: 0 })
    await disableCustomerTag(9)
    await enableCustomerTag(9)

    expect(calls.map((c) => `${c.method.toUpperCase()} ${c.url}`)).toEqual([
      'GET /customer-tags',
      'POST /customer-tags',
      'PATCH /customer-tags/9',
      'POST /customer-tags/9/disable',
      'POST /customer-tags/9/enable',
    ])
    expect(calls[1]?.data).toEqual({ optionLabel: '敏感肌', sortNo: 6 })
    expect(calls[2]?.data).toEqual({ optionLabel: '极敏感肌', sortNo: 7, version: 0 })
  })

  it('客户来源与渠道管理端点契约（/customer-sources、/customer-channels）', async () => {
    const { adapter, calls } = captureAdapter([])
    http.defaults.adapter = adapter

    await listCustomerSources()
    await createCustomerSource({ optionLabel: '口碑转介绍', sortNo: 3 })
    await updateCustomerSource(11, { optionLabel: '老客口碑转介绍', sortNo: 2, version: 1 })
    await disableCustomerSource(11)
    await enableCustomerSource(11)

    await listCustomerChannels()
    await createCustomerChannel({ optionLabel: '小红书笔记', sortNo: 4 })
    await updateCustomerChannel(21, { optionLabel: '小红书达人', sortNo: 5, version: 0 })
    await disableCustomerChannel(21)
    await enableCustomerChannel(21)

    expect(calls.map((c) => `${c.method.toUpperCase()} ${c.url}`)).toEqual([
      'GET /customer-sources',
      'POST /customer-sources',
      'PATCH /customer-sources/11',
      'POST /customer-sources/11/disable',
      'POST /customer-sources/11/enable',
      'GET /customer-channels',
      'POST /customer-channels',
      'PATCH /customer-channels/21',
      'POST /customer-channels/21/disable',
      'POST /customer-channels/21/enable',
    ])
    expect(calls[1]?.data).toEqual({ optionLabel: '口碑转介绍', sortNo: 3 })
    expect(calls[7]?.data).toEqual({ optionLabel: '小红书达人', sortNo: 5, version: 0 })
  })

  it('多管家唯一性排查端点契约（summary / multi-stewards / steward-relations）', async () => {
    const { adapter, calls } = captureAdapter({ items: [], total: 0, page: 1, size: 20 })
    http.defaults.adapter = adapter

    await getMultiStewardSummary()
    await listMultiStewardCustomers({ page: 1, size: 20, keyword: '陈女士', minStewards: 2, crossTeamOnly: true })
    await getCustomerStewardRelations(7)

    expect(calls.map((c) => `${c.method.toUpperCase()} ${c.url}`)).toEqual([
      'GET /customer-uniqueness/summary',
      'GET /customer-uniqueness/multi-stewards',
      'GET /customers/7/steward-relations',
    ])
    expect(calls[1]?.params).toEqual({
      page: 1,
      size: 20,
      keyword: '陈女士',
      minStewards: 2,
      crossTeamOnly: true,
    })
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

  it('主管直接分配（单客/批量）与归属日志/转交单分页契约', async () => {
    const { adapter, calls } = captureAdapter({ items: [], total: 0, page: 1, size: 20 })
    http.defaults.adapter = adapter

    await assignCustomerOwner(7, { newOwnerId: 12, reason: 'B休假分给C', version: 2 }, 'assign-idem-1')
    await batchAssignCustomerOwners(
      { items: [{ customerId: 7, version: 2 }, { customerId: 8, version: 1 }], newOwnerId: 12, reason: '批量分流' },
      'assign-batch-1',
    )
    await listCustomerOwnershipLogs({ page: 1, size: 20, ownerId: 12, keyword: '陈女士' })
    await listScopedOwnershipTransfers({ page: 2, size: 10, status: 'PENDING', ownerId: 2, keyword: '' })

    expect(calls.map((call) => `${call.method} ${call.url}`)).toEqual([
      'post /customers/7/owner-assignment',
      'post /customers/owner-assignments/batch',
      'get /customer-ownership-history',
      'get /customer-ownership-transfers',
    ])
    expect(calls[0]?.data).toEqual({ newOwnerId: 12, reason: 'B休假分给C', version: 2 })
    expect(calls[0]?.headers['Idempotency-Key']).toBe('assign-idem-1')
    expect(calls[1]?.data).toEqual({
      items: [{ customerId: 7, version: 2 }, { customerId: 8, version: 1 }],
      newOwnerId: 12,
      reason: '批量分流',
    })
    expect(calls[1]?.headers['Idempotency-Key']).toBe('assign-batch-1')
    expect(calls[2]?.params).toEqual({
      page: 1,
      size: 20,
      customerId: undefined,
      ownerId: 12,
      keyword: '陈女士',
    })
    expect(calls[3]?.params).toEqual({
      page: 2,
      size: 10,
      status: 'PENDING',
      ownerId: 2,
      keyword: undefined,
    })
  })
})
