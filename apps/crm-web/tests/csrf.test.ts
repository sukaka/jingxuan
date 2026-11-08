import type { AxiosAdapter, AxiosHeaders, InternalAxiosRequestConfig } from 'axios'
import { afterEach, describe, expect, it } from 'vitest'
import { CSRF_COOKIE_NAME, CSRF_HEADER_NAME, http } from '../src/api/http'

const originalAdapter = http.defaults.adapter

function clearCsrfCookie() {
  document.cookie = `${CSRF_COOKIE_NAME}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/`
}

/** 捕获请求头的 adapter,返回固定 OK 业务响应 */
function captureHeaders() {
  const captured: { headers: AxiosHeaders | null; method: string | null; url: string | null } = {
    headers: null,
    method: null,
    url: null,
  }
  const adapter: AxiosAdapter = async (config: InternalAxiosRequestConfig) => {
    captured.headers = config.headers
    captured.method = config.method ?? null
    captured.url = config.url ?? null
    return {
      status: 200,
      statusText: 'OK',
      headers: {},
      config,
      data: { code: 'OK', message: 'success', data: null, requestId: 'req-test', timestamp: '2026-10-23T00:00:00Z' },
    }
  }
  return { captured, adapter }
}

afterEach(() => {
  http.defaults.adapter = originalAdapter
  clearCsrfCookie()
})

describe('CSRF 头注入', () => {
  it('写方法(POST)携带 X-CSRF-Token,值等于 CRM_CSRF Cookie', async () => {
    document.cookie = `${CSRF_COOKIE_NAME}=csrf-token-abc123`
    const { captured, adapter } = captureHeaders()
    http.defaults.adapter = adapter

    await http.post('/users', { username: 'x' })

    expect(captured.headers?.get(CSRF_HEADER_NAME)).toBe('csrf-token-abc123')
  })

  it('PATCH/PUT/DELETE 同样注入 CSRF 头', async () => {
    document.cookie = `${CSRF_COOKIE_NAME}=csrf-token-xyz`
    const { captured, adapter } = captureHeaders()
    http.defaults.adapter = adapter

    await http.patch('/users/1', { version: 3 })
    expect(captured.headers?.get(CSRF_HEADER_NAME)).toBe('csrf-token-xyz')

    await http.put('/users/1/roles', { roleIds: [1] })
    expect(captured.headers?.get(CSRF_HEADER_NAME)).toBe('csrf-token-xyz')

    await http.delete('/users/1')
    expect(captured.headers?.get(CSRF_HEADER_NAME)).toBe('csrf-token-xyz')
  })

  it('读方法(GET)不注入 CSRF 头', async () => {
    document.cookie = `${CSRF_COOKIE_NAME}=csrf-token-abc123`
    const { captured, adapter } = captureHeaders()
    http.defaults.adapter = adapter

    await http.get('/users')

    expect(captured.headers?.get(CSRF_HEADER_NAME)).toBeFalsy()
  })

  it('登录接口 /auth/login 豁免 CSRF 头(尚无会话 Cookie)', async () => {
    document.cookie = `${CSRF_COOKIE_NAME}=csrf-token-abc123`
    const { captured, adapter } = captureHeaders()
    http.defaults.adapter = adapter

    await http.post('/auth/login', { username: 'admin', password: 'x' })

    expect(captured.headers?.get(CSRF_HEADER_NAME)).toBeFalsy()
  })

  it('没有 CRM_CSRF Cookie 时不注入(后端将返回 403 CSRF_TOKEN_INVALID)', async () => {
    const { captured, adapter } = captureHeaders()
    http.defaults.adapter = adapter

    await http.post('/users', { username: 'x' })

    expect(captured.headers?.get(CSRF_HEADER_NAME)).toBeFalsy()
  })
})
