import type { AxiosAdapter } from 'axios'
import { afterEach, describe, expect, it } from 'vitest'
import {
  getWecomMessageContent,
  getWecomMonitor,
  linkWecomContact,
  listWecomContacts,
  listWecomContactSyncRuns,
  listWecomMessages,
  syncWecomArchive,
  syncWecomContacts,
} from '../src/api/wecom'
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
    return { status: 200, statusText: 'OK', headers: {}, config, data: { code: 'OK', message: 'success', data: response, requestId: 'wecom-test', timestamp: '' } }
  }
  return { adapter, calls }
}

describe('P1-2 企业微信归档 API 契约', () => {
  it('消息分页、联系人、监控和按需内容使用真实 Controller 路径与参数', async () => {
    const { adapter, calls } = capture()
    http.defaults.adapter = adapter

    await listWecomMessages({ customerId: 42, page: 2, size: 20 })
    await listWecomContacts()
    await getWecomMonitor('MOCK-CORP-001')
    await getWecomMessageContent(901)

    expect(calls.map((call) => `${call.method} ${call.url}`)).toEqual([
      'get /integrations/wecom/messages',
      'get /integrations/wecom/contacts',
      'get /integrations/wecom/monitor',
      'get /integrations/wecom/messages/901/content',
    ])
    expect(calls[0]?.params).toEqual({ customerId: 42, page: 2, size: 20 })
    expect(calls[2]?.params).toEqual({ corpId: 'MOCK-CORP-001' })
  })

  it('Mock 同步传 corpId/cursor/limit，人工 link 传 customerId/version/reason 和幂等键', async () => {
    const { adapter, calls } = capture()
    http.defaults.adapter = adapter

    await syncWecomArchive({ corpId: 'MOCK-CORP-001', cursor: 100, limit: 50 }, 'sync-key')
    await linkWecomContact(2, { customerId: 42, version: 3, reason: '人工核对外部身份与CRM档案' }, 'link-key')

    expect(calls[0]?.url).toBe('/integrations/wecom/sync')
    expect(calls[0]?.data).toEqual({ corpId: 'MOCK-CORP-001', cursor: 100, limit: 50 })
    expect(calls[0]?.key).toBe('sync-key')
    expect(calls[1]?.url).toBe('/integrations/wecom/contacts/2/link')
    expect(calls[1]?.data).toEqual({ customerId: 42, version: 3, reason: '人工核对外部身份与CRM档案' })
    expect(calls[1]?.key).toBe('link-key')
  })

  it('客户资料全量与对账使用独立接口，运行记录不复用归档游标', async () => {
    const { adapter, calls } = capture([])
    http.defaults.adapter = adapter

    await syncWecomContacts('RECONCILE', 'contact-sync-key')
    await listWecomContactSyncRuns()

    expect(calls[0]?.url).toBe('/integrations/wecom/contact-sync')
    expect(calls[0]?.data).toEqual({ type: 'RECONCILE' })
    expect(calls[0]?.key).toBe('contact-sync-key')
    expect(calls[1]?.method).toBe('get')
    expect(calls[1]?.url).toBe('/integrations/wecom/contact-sync/runs')
  })
})
