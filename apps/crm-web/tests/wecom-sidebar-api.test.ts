import type { AxiosAdapter } from 'axios'
import { afterEach, describe, expect, it } from 'vitest'
import { CSRF_COOKIE_NAME, CSRF_HEADER_NAME, http } from '../src/api/http'
import { getWecomSidebarContext, getWecomSidebarSignature } from '../src/api/wecomSidebar'

const originalAdapter = http.defaults.adapter

afterEach(() => {
  http.defaults.adapter = originalAdapter
  document.cookie = `${CSRF_COOKIE_NAME}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/`
})

describe('企微侧边栏 API', () => {
  it('签名携带 CSRF，客户上下文只传 externalUserid', async () => {
    document.cookie = `${CSRF_COOKIE_NAME}=sidebar-csrf`
    const calls: Array<Record<string, unknown>> = []
    const adapter: AxiosAdapter = async (config) => {
      calls.push({
        method: config.method,
        url: config.url,
        data: typeof config.data === 'string' ? JSON.parse(config.data) : config.data,
        params: config.params,
        headers: config.headers.toJSON(),
      })
      return {
        status: 200, statusText: 'OK', headers: {}, config,
        data: { code: 'OK', message: 'success', data: {}, requestId: 'req-sidebar', timestamp: '' },
      }
    }
    http.defaults.adapter = adapter

    await getWecomSidebarSignature('https://crm.example.com/wecom/sidebar')
    await getWecomSidebarContext('external-1001')

    expect(calls[0]).toMatchObject({
      method: 'post', url: '/wecom/jssdk/signature',
      data: { url: 'https://crm.example.com/wecom/sidebar' },
      headers: { [CSRF_HEADER_NAME]: 'sidebar-csrf' },
    })
    expect(calls[1]).toMatchObject({
      method: 'get', url: '/wecom/sidebar/context', params: { externalUserid: 'external-1001' },
    })
    expect(calls[1]?.data).toBeUndefined()
  })
})
