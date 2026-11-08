import type { AxiosAdapter } from 'axios'
import { afterEach, describe, expect, it } from 'vitest'
import {
  getYouzanReconcileReport,
  listYouzanCustomerLinks,
  listYouzanOrders,
  listYouzanSkuMappings,
  listYouzanSyncIssues,
  reconcileYouzanOrders,
  retryYouzanSyncIssue,
  saveYouzanCustomerLink,
  saveYouzanSkuMapping,
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

    await syncYouzanOrders({ shopId: 'MOCK-SHOP-001', cursor: '2', limit: 50 }, 'sync-key')
    await reconcileYouzanOrders({ shopId: 'MOCK-SHOP-001', limit: 50 }, 'reconcile-key')

    expect(calls[0]?.url).toBe('/integrations/youzan/sync')
    expect(calls[0]?.data).toEqual({ shopId: 'MOCK-SHOP-001', cursor: '2', limit: 50 })
    expect(calls[0]?.key).toBe('sync-key')
    expect(calls[1]?.url).toBe('/integrations/youzan/reconcile')
    expect(calls[1]?.data).toEqual({ shopId: 'MOCK-SHOP-001', limit: 50 })
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
})
