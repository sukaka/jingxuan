import type { AxiosAdapter } from 'axios'
import { afterEach, describe, expect, it } from 'vitest'
import { createChannel, listChannels, updateChannel } from '../src/api/channel'
import { http } from '../src/api/http'

const originalAdapter = http.defaults.adapter
afterEach(() => { http.defaults.adapter = originalAdapter })

function capture(response: unknown) {
  const calls: { method: string; url: string; data: unknown; params: unknown; idempotencyKey?: string }[] = []
  const adapter: AxiosAdapter = async (config) => {
    calls.push({
      method: config.method ?? 'get',
      url: config.url ?? '',
      data: config.data ? JSON.parse(config.data as string) : undefined,
      params: config.params,
      idempotencyKey: config.headers?.get('Idempotency-Key')?.toString(),
    })
    return {
      status: 200,
      statusText: 'OK',
      headers: {},
      config,
      data: { code: 'OK', message: 'success', data: response, requestId: 'channel-test', timestamp: '' },
    }
  }
  return { adapter, calls }
}

describe('P2 渠道 API 契约', () => {
  it('列表只从 /channels 读取并保留筛选分页', async () => {
    const { adapter, calls } = capture({ items: [], total: 0, page: 1, size: 20 })
    http.defaults.adapter = adapter

    await listChannels({ page: 2, size: 20, keyword: '测试渠道', status: 'ACTIVE' })

    expect(calls[0]?.method).toBe('get')
    expect(calls[0]?.url).toBe('/channels')
    expect(calls[0]?.params).toEqual({ page: 2, size: 20, keyword: '测试渠道', status: 'ACTIVE' })
  })

  it('创建与修改只提交实际 DTO 渠道主数据，不伪造线索、订单或商务姓名', async () => {
    const { adapter, calls } = capture({ id: 91 })
    http.defaults.adapter = adapter

    await createChannel({
      channelNo: 'CH-TEST',
      name: '测试渠道',
      platformCode: 'XHS',
      businessOwnerUserId: 8,
      remarks: '契约测试',
    })
    await updateChannel(91, {
      name: '测试渠道（调整）',
      platformCode: 'XHS',
      businessOwnerUserId: 8,
      status: 'INACTIVE',
      remarks: '暂停合作',
      version: 2,
    })

    expect(calls[0]?.method).toBe('post')
    expect(calls[0]?.url).toBe('/channels')
    expect(calls[0]?.data).toEqual({
      channelNo: 'CH-TEST',
      name: '测试渠道',
      platformCode: 'XHS',
      businessOwnerUserId: 8,
      remarks: '契约测试',
    })
    expect(calls[1]?.method).toBe('patch')
    expect(calls[1]?.url).toBe('/channels/91')
    expect(calls[1]?.data).toEqual({
      name: '测试渠道（调整）',
      platformCode: 'XHS',
      businessOwnerUserId: 8,
      status: 'INACTIVE',
      remarks: '暂停合作',
      version: 2,
    })
  })
})
