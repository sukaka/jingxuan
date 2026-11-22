import type { AxiosAdapter } from 'axios'
import { afterEach, describe, expect, it } from 'vitest'
import { CSRF_COOKIE_NAME, CSRF_HEADER_NAME, http } from '../src/api/http'
import {
  getWecomSidebarContext,
  getWecomSidebarGroupContext,
  getWecomSidebarSignature,
  linkWecomSidebarContact,
  uploadWecomMaterialMedia,
} from '../src/api/wecomSidebar'

const originalAdapter = http.defaults.adapter

afterEach(() => {
  http.defaults.adapter = originalAdapter
  document.cookie = `${CSRF_COOKIE_NAME}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/`
})

describe('企微侧边栏 API', () => {
  it('签名、群聊上下文、侧边栏关联客户与素材上传携带正确方法、参数和 CSRF', async () => {
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
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
        data: { code: 'OK', message: 'success', data: {}, requestId: 'req-sidebar', timestamp: '' },
      }
    }
    http.defaults.adapter = adapter

    await getWecomSidebarSignature('https://crm.example.com/wecom/sidebar')
    await getWecomSidebarContext('external-1001')
    await getWecomSidebarGroupContext('wr-room-01')
    await linkWecomSidebarContact({ externalUserid: 'external-1001', customerId: 1010, reason: '管家人工核对关联' })
    await uploadWecomMaterialMedia(42)

    expect(calls[0]).toMatchObject({
      method: 'post',
      url: '/wecom/jssdk/signature',
      data: { url: 'https://crm.example.com/wecom/sidebar' },
      headers: { [CSRF_HEADER_NAME]: 'sidebar-csrf' },
    })
    expect(calls[1]).toMatchObject({
      method: 'get',
      url: '/wecom/sidebar/context',
      params: { externalUserid: 'external-1001' },
    })
    expect(calls[1]?.data).toBeUndefined()
    expect(calls[2]).toMatchObject({
      method: 'get',
      url: '/wecom/sidebar/group-context',
      params: { chatId: 'wr-room-01' },
    })
    expect(calls[3]).toMatchObject({
      method: 'post',
      url: '/wecom/sidebar/contacts/link',
      data: { externalUserid: 'external-1001', customerId: 1010, reason: '管家人工核对关联' },
      headers: { [CSRF_HEADER_NAME]: 'sidebar-csrf' },
    })
    expect(calls[4]).toMatchObject({
      method: 'post',
      url: '/wecom/sidebar/materials/42/media',
      headers: { [CSRF_HEADER_NAME]: 'sidebar-csrf' },
    })
  })
})
