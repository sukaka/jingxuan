import type { AxiosAdapter } from 'axios'
import { afterEach, describe, expect, it } from 'vitest'
import {
  assignYouzanOrderSteward,
  batchRetryYouzanSyncIssues,
  deleteYouzanStewardAlias,
  getYouzanCustomerSyncStatus,
  getYouzanReconcileReport,
  listYouzanCustomerLinks,
  listYouzanCustomerSyncRunItems,
  listYouzanCustomerSyncRuns,
  listYouzanOrders,
  listYouzanSkuCandidates,
  listYouzanSkuMappings,
  listYouzanStewardAliases,
  listYouzanStewardOptions,
  listYouzanSyncIssues,
  pullYouzanCustomers,
  pullYouzanOrderByTid,
  reconcileYouzanOrders,
  resetYouzanCustomerSyncCursor,
  retryYouzanSyncIssue,
  saveYouzanCustomerLink,
  saveYouzanSkuMapping,
  saveYouzanStewardAlias,
  syncYouzanOrders,
} from '../src/api/youzan'
import { http } from '../src/api/http'

const originalAdapter = http.defaults.adapter
afterEach(() => { http.defaults.adapter = originalAdapter })

function capture(response: unknown = {}) {
  const calls: { method: string; url: string; data: unknown; params: unknown; key: unknown }[] = []
  const adapter: AxiosAdapter = async (config) => {
    calls.push({
      method: config.method ?? 'get',
      url: config.url ?? '',
      data: typeof config.data === 'string' ? JSON.parse(config.data) : config.data,
      params: config.params,
      key: config.headers?.get('Idempotency-Key'),
    })
    return { status: 200, statusText: 'OK', headers: {}, config, data: { code: 'OK', message: 'success', data: response, requestId: 'youzan-test', timestamp: '' } }
  }
  return { adapter, calls }
}

describe('P1-1 有赞 Mock API 契约', () => {
  it('订单、对账和异常列表使用真实查询参数', async () => {
    const { adapter, calls } = capture()
    http.defaults.adapter = adapter

    await listYouzanOrders({ shopId: 'MOCK-SHOP-001', page: 2, size: 20 })
    await getYouzanReconcileReport('MOCK-SHOP-001')
    await listYouzanSyncIssues({ status: 'OPEN', page: 1, size: 20 })
    await listYouzanSkuMappings('MOCK-SHOP-001')
    await listYouzanCustomerLinks('MOCK-SHOP-001')

    expect(calls.map((call) => call.url)).toEqual(['/integrations/youzan/orders', '/integrations/youzan/reconcile-report', '/integrations/youzan/sync-issues', '/integrations/youzan/sku-mappings', '/integrations/youzan/customer-links'])
    expect(calls[0]?.params).toEqual({ shopId: 'MOCK-SHOP-001', page: 2, size: 20 })
    expect(calls[2]?.params).toEqual({ status: 'OPEN', page: 1, size: 20 })
  })

  it('Mock 同步与补偿对账携带游标、页大小和幂等键', async () => {
    const { adapter, calls } = capture()
    http.defaults.adapter = adapter

    await syncYouzanOrders({ shopId: 'MOCK-SHOP-001', cursor: '2', limit: 20 }, 'sync-key')
    await reconcileYouzanOrders({ shopId: 'MOCK-SHOP-001', limit: 10, maxOrders: 10 }, 'reconcile-key')

    expect(calls[0]?.url).toBe('/integrations/youzan/sync')
    expect(calls[0]?.data).toEqual({ shopId: 'MOCK-SHOP-001', cursor: '2', limit: 20 })
    expect(calls[0]?.key).toBe('sync-key')
    expect(calls[1]?.url).toBe('/integrations/youzan/reconcile')
    expect(calls[1]?.data).toEqual({ shopId: 'MOCK-SHOP-001', limit: 10, maxOrders: 10 })
    expect(calls[1]?.key).toBe('reconcile-key')
  })

  it('异常重试、SKU 映射和人工客户链接均使用真实端点与幂等键', async () => {
    const { adapter, calls } = capture()
    http.defaults.adapter = adapter

    await retryYouzanSyncIssue(801, 'retry-key')
    await saveYouzanSkuMapping({ shopId: 'MOCK-SHOP-001', externalSkuId: 'YZ-SKU-301', crmSkuId: 301, version: 2 }, 'sku-key')
    await saveYouzanCustomerLink({ shopId: 'MOCK-SHOP-001', externalCustomerId: 'YZ-CUSTOMER-1', customerId: 42, version: 1, reason: '人工核对订单与CRM档案' }, 'customer-key')

    expect(calls[0]?.url).toBe('/integrations/youzan/sync-issues/801/retry')
    expect(calls[0]?.key).toBe('retry-key')
    expect(calls[1]?.url).toBe('/integrations/youzan/sku-mappings')
    expect(calls[1]?.data).toEqual({ shopId: 'MOCK-SHOP-001', externalSkuId: 'YZ-SKU-301', crmSkuId: 301, version: 2 })
    expect(calls[1]?.key).toBe('sku-key')
    expect(calls[2]?.url).toBe('/integrations/youzan/customer-links')
    expect(calls[2]?.data).toMatchObject({ customerId: 42, version: 1, reason: '人工核对订单与CRM档案' })
    expect(calls[2]?.key).toBe('customer-key')
  })

  it('待映射 SKU 候选查询与手动批量重试异常使用真实端点、参数与幂等键', async () => {
    const { adapter, calls } = capture()
    http.defaults.adapter = adapter

    await listYouzanSkuCandidates('40880001')
    await batchRetryYouzanSyncIssues({ shopId: '40880001', externalSkuId: '5505085839:26076889518', limit: 50 }, 'batch-retry-sku-key')
    await batchRetryYouzanSyncIssues({ shopId: '40880001', limit: 50 }, 'batch-retry-all-key')

    expect(calls[0]).toMatchObject({
      method: 'get',
      url: '/integrations/youzan/sku-candidates',
      params: { shopId: '40880001' },
    })
    expect(calls[1]).toMatchObject({
      method: 'post',
      url: '/integrations/youzan/sync-issues/batch-retry',
      data: { shopId: '40880001', externalSkuId: '5505085839:26076889518', limit: 50 },
      key: 'batch-retry-sku-key',
    })
    expect(calls[2]).toMatchObject({
      method: 'post',
      url: '/integrations/youzan/sync-issues/batch-retry',
      data: { shopId: '40880001', limit: 50 },
      key: 'batch-retry-all-key',
    })
  })

  it('有赞客户拉取建档 5 个端点使用契约路径、参数与幂等键', async () => {
    const { adapter, calls } = capture()
    http.defaults.adapter = adapter

    await getYouzanCustomerSyncStatus('MOCK-SHOP-001')
    await pullYouzanCustomers({
      shopId: 'MOCK-SHOP-001',
      sourceMode: 'SCRM_LIST',
      pullMode: 'BATCH',
      limit: 10,
      ownerStaffId: 9,
      sourceCode: 'YOUZAN_MALL',
      channelCode: 'MINI_PROGRAM',
      startDate: '2026-10-01',
      endDate: '2026-11-26',
      enrichProfile: true,
    }, 'customer-pull-key-1')
    await resetYouzanCustomerSyncCursor({ shopId: 'MOCK-SHOP-001', sourceMode: 'ORDER_BUYERS' })
    await listYouzanCustomerSyncRuns({ shopId: 'MOCK-SHOP-001', sourceMode: 'SCRM_LIST', page: 1, size: 10 })
    await listYouzanCustomerSyncRunItems(501, { outcome: 'MOBILE_CONFLICT', page: 2, size: 20 })

    expect(calls[0]).toMatchObject({
      method: 'get',
      url: '/integrations/youzan/customer-sync/status',
      params: { shopId: 'MOCK-SHOP-001' },
    })
    expect(calls[1]).toMatchObject({
      method: 'post',
      url: '/integrations/youzan/customer-sync/pull',
      data: {
        shopId: 'MOCK-SHOP-001',
        sourceMode: 'SCRM_LIST',
        pullMode: 'BATCH',
        limit: 10,
        ownerStaffId: 9,
        sourceCode: 'YOUZAN_MALL',
        channelCode: 'MINI_PROGRAM',
        startDate: '2026-10-01',
        endDate: '2026-11-26',
        enrichProfile: true,
      },
      key: 'customer-pull-key-1',
    })
    expect(calls[2]).toMatchObject({
      method: 'post',
      url: '/integrations/youzan/customer-sync/cursor/reset',
      data: { shopId: 'MOCK-SHOP-001', sourceMode: 'ORDER_BUYERS' },
    })
    expect(calls[3]).toMatchObject({
      method: 'get',
      url: '/integrations/youzan/customer-sync/runs',
      params: { shopId: 'MOCK-SHOP-001', sourceMode: 'SCRM_LIST', page: 1, size: 10 },
    })
    expect(calls[4]).toMatchObject({
      method: 'get',
      url: '/integrations/youzan/customer-sync/runs/501/items',
      params: { outcome: 'MOBILE_CONFLICT', page: 2, size: 20 },
    })
  })

  it('按单号拉取、管家筛选、人工分配与账号别名映射使用契约路径与幂等键', async () => {
    const { adapter, calls } = capture()
    http.defaults.adapter = adapter

    await listYouzanOrders({ shopId: '92010731', page: 1, size: 20, assignedStewardId: 9, assignmentStatus: 'ASSIGNED', keyword: '紫紫' })
    await pullYouzanOrderByTid({ shopId: '92010731', tid: 'E202611110001' }, 'pull-tid-key')
    await assignYouzanOrderSteward('92010731', 'E202611110001', { stewardUserId: 9, reason: '人工确认', version: 0, saveAlias: true, aliasName: '紫紫' }, 'assign-key')
    await listYouzanStewardOptions()
    await listYouzanStewardAliases()
    await saveYouzanStewardAlias({ aliasName: '紫紫', stewardUserId: 9, status: 'ACTIVE', backfillPending: true }, 'alias-key')
    await deleteYouzanStewardAlias(12)

    expect(calls[0]).toMatchObject({
      method: 'get',
      url: '/integrations/youzan/orders',
      params: { shopId: '92010731', page: 1, size: 20, assignedStewardId: 9, assignmentStatus: 'ASSIGNED', keyword: '紫紫' },
    })
    expect(calls[1]).toMatchObject({
      method: 'post',
      url: '/integrations/youzan/orders/pull-by-tid',
      data: { shopId: '92010731', tid: 'E202611110001' },
      key: 'pull-tid-key',
    })
    expect(calls[2]).toMatchObject({
      method: 'put',
      url: '/integrations/youzan/orders/E202611110001/assign',
      params: { shopId: '92010731' },
      data: { stewardUserId: 9, reason: '人工确认', version: 0, saveAlias: true, aliasName: '紫紫' },
      key: 'assign-key',
    })
    expect(calls[3]).toMatchObject({ method: 'get', url: '/integrations/youzan/steward-options' })
    expect(calls[4]).toMatchObject({ method: 'get', url: '/integrations/youzan/steward-aliases' })
    expect(calls[5]).toMatchObject({
      method: 'post',
      url: '/integrations/youzan/steward-aliases',
      data: { aliasName: '紫紫', stewardUserId: 9, status: 'ACTIVE', backfillPending: true },
      key: 'alias-key',
    })
    expect(calls[6]).toMatchObject({ method: 'delete', url: '/integrations/youzan/steward-aliases/12' })
  })
})
