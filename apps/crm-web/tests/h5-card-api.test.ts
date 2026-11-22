import type { AxiosAdapter } from 'axios'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createH5Card, disableH5Card, getPublicH5Card, listH5Cards, recordH5CardUsage } from '../src/api/h5Card'
import { CSRF_COOKIE_NAME, CSRF_HEADER_NAME, http } from '../src/api/http'

const originalAdapter = http.defaults.adapter

afterEach(() => {
  http.defaults.adapter = originalAdapter
  document.cookie = `${CSRF_COOKIE_NAME}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/`
})

describe('H5 卡片 API 契约', () => {
  it('写操作带 CSRF 与幂等键，公开读取不带业务身份字段', async () => {
    document.cookie = `${CSRF_COOKIE_NAME}=h5-csrf`
    const calls: Array<Record<string, unknown>> = []
    const adapter: AxiosAdapter = async (config) => {
      calls.push({
        method: config.method, url: config.url, params: config.params,
        data: typeof config.data === 'string' ? JSON.parse(config.data) : undefined,
        headers: config.headers.toJSON(),
      })
      return { status: 200, statusText: 'OK', headers: {}, config, data: { code: 'OK', message: 'success', data: {}, requestId: 'req-h5', timestamp: '' } }
    }
    http.defaults.adapter = adapter
    const input = {
      customerId: 1010, projectId: 101, externalUserid: 'external-1001', title: '项目介绍', summary: '服务流程', content: '公开介绍',
    }
    await createH5Card(input, 'h5-create')
    await listH5Cards(1010)
    await recordH5CardUsage(31, { customerId: 1010, sendStatus: 'SDK_UNKNOWN', clientResult: 'timeout' }, 'h5-usage')
    await disableH5Card(31, { version: 1, reason: '修订介绍' }, 'h5-disable')
    await getPublicH5Card('high-entropy-share-key')

    expect(calls.map((call) => [call.method, call.url])).toEqual([
      ['post', '/h5/cards'], ['get', '/h5/cards'], ['post', '/h5/cards/31/usage'],
      ['post', '/h5/cards/31/disable'], ['get', '/public/h5-cards/high-entropy-share-key'],
    ])
    expect(calls[0]?.data).toEqual(input)
    expect(calls[0]?.data).not.toHaveProperty('tenantId')
    expect(calls[1]?.params).toEqual({ customerId: 1010, size: 20 })
    for (const [index, key] of ['h5-create', 'h5-usage', 'h5-disable'].entries()) {
      const callIndex = index === 0 ? 0 : index + 1
      expect(calls[callIndex]?.headers).toMatchObject({ 'Idempotency-Key': key, [CSRF_HEADER_NAME]: 'h5-csrf' })
    }
  })

  it.each(['', 'short', '../secret', '..%2Fsecret', 'key-with-query?x=1', 'key#hash'])('拒绝非法 shareKey "%s"，不发起任何 HTTP 请求', async (badKey) => {
    const adapter = vi.fn()
    http.defaults.adapter = adapter as unknown as AxiosAdapter

    await expect(getPublicH5Card(badKey)).rejects.toMatchObject({
      name: 'ApiError',
      status: 400,
      code: 'INVALID_SHARE_KEY',
    })
    expect(adapter).not.toHaveBeenCalled()
  })
})
