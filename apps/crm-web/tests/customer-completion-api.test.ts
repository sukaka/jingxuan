import type { AxiosAdapter } from 'axios'
import { afterEach, describe, expect, it } from 'vitest'
import {
  createCustomer,
  getCustomerLegacyProfile,
  listCustomerOwnerOptions,
  scanMergeCandidates,
} from '../src/api/customer'
import { http } from '../src/api/http'

const originalAdapter = http.defaults.adapter

afterEach(() => {
  http.defaults.adapter = originalAdapter
})

function capture(response: unknown) {
  const calls: { method: string; url: string; data: unknown }[] = []
  const adapter: AxiosAdapter = async (config) => {
    calls.push({
      method: config.method ?? 'get',
      url: config.url ?? '',
      data: config.data ? JSON.parse(config.data as string) : undefined,
    })
    return {
      status: 200,
      statusText: 'OK',
      headers: {},
      config,
      data: { code: 'OK', message: 'success', data: response, requestId: 'req', timestamp: '' },
    }
  }
  return { adapter, calls }
}

describe('客户域联调补齐契约', () => {
  it('查重扫描走独立端点并保留 created/items 结果', async () => {
    const { adapter, calls } = capture({ created: 2, items: [{ id: 9 }, { id: 10 }] })
    http.defaults.adapter = adapter

    const result = await scanMergeCandidates()

    expect(calls).toEqual([{ method: 'post', url: '/customer-merge-candidates/scan', data: undefined }])
    expect(result.created).toBe(2)
    expect(result.items).toHaveLength(2)
  })

  it('管家选项和原23字段档案使用真实后端端点', async () => {
    const { adapter, calls } = capture([])
    http.defaults.adapter = adapter

    await listCustomerOwnerOptions()
    await getCustomerLegacyProfile(42)

    expect(calls.map((call) => [call.method, call.url])).toEqual([
      ['get', '/customer-owner-options'],
      ['get', '/customers/42/legacy-profile'],
    ])
  })

  it('建档显式携带所属管家和带时区的首次添加时间', async () => {
    const { adapter, calls } = capture({ id: 42 })
    http.defaults.adapter = adapter

    await createCustomer({
      nickname: '契约客户',
      ownerStaffId: 2,
      firstAddedAt: '2026-10-24T00:30:00+08:00',
    })

    expect(calls[0]?.data).toEqual({
      nickname: '契约客户',
      ownerStaffId: 2,
      firstAddedAt: '2026-10-24T00:30:00+08:00',
    })
  })
})
