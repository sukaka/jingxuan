import { AxiosError, AxiosHeaders } from 'axios'
import type { AxiosAdapter, AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { afterEach, describe, expect, it } from 'vitest'
import { getDictionaryOptions } from '../src/api/dictionary'
import { http, normalizeApiError } from '../src/api/http'
import { ApiError } from '../src/types/api'

const originalAdapter = http.defaults.adapter

afterEach(() => {
  http.defaults.adapter = originalAdapter
})

function makeConfig(): InternalAxiosRequestConfig {
  return { headers: new AxiosHeaders(), url: '/secure', method: 'get' } as InternalAxiosRequestConfig
}

describe('normalizeApiError', () => {
  it('401 响应体规范化为 ApiError,保留后端 code/message/requestId/details(如登录失败"用户名或密码错误")', () => {
    const config = makeConfig()
    const response: AxiosResponse = {
      status: 401,
      statusText: 'Unauthorized',
      headers: {},
      config,
      data: {
        code: 'UNAUTHORIZED',
        message: '用户名或密码错误',
        requestId: 'req-401-abc',
        details: { reason: 'bad credentials' },
      },
    }
    const axiosError = new AxiosError(
      'Request failed with status code 401',
      'ERR_BAD_REQUEST',
      config,
      {},
      response,
    )

    const apiError = normalizeApiError(axiosError)

    expect(apiError).toBeInstanceOf(ApiError)
    expect(apiError.status).toBe(401)
    expect(apiError.code).toBe('UNAUTHORIZED')
    expect(apiError.requestId).toBe('req-401-abc')
    expect(apiError.details).toEqual({ reason: 'bad credentials' })
    expect(apiError.message).toBe('用户名或密码错误')
    expect(apiError.isUnauthorized).toBe(true)
  })

  it('401 无后端 message 时回退到统一的重新登录提示', () => {
    const config = makeConfig()
    const response: AxiosResponse = {
      status: 401,
      statusText: 'Unauthorized',
      headers: {},
      config,
      data: { code: 'UNAUTHORIZED' },
    }
    const axiosError = new AxiosError(
      'Request failed with status code 401',
      'ERR_BAD_REQUEST',
      config,
      {},
      response,
    )

    const apiError = normalizeApiError(axiosError)

    expect(apiError.message).toContain('请重新登录')
    expect(apiError.isUnauthorized).toBe(true)
  })

  it('后端未启动时代理返回的 5xx HTML 错误页规范化为无法连接', () => {
    const config = makeConfig()
    const response: AxiosResponse = {
      status: 502,
      statusText: 'Bad Gateway',
      headers: {},
      config,
      data: '<html><body>Error: connect ECONNREFUSED 127.0.0.1:8080</body></html>',
    }
    const axiosError = new AxiosError(
      'Request failed with status code 502',
      'ERR_BAD_RESPONSE',
      config,
      {},
      response,
    )

    const apiError = normalizeApiError(axiosError)

    expect(apiError.status).toBe(0)
    expect(apiError.code).toBe('NETWORK_ERROR')
    expect(apiError.message).toContain('无法连接后端服务')
    expect(apiError.isNetworkError).toBe(true)
  })

  it('无响应的网络错误规范化为 status 0 的后端未启动提示', () => {
    const config = makeConfig()
    const axiosError = new AxiosError('Network Error', 'ERR_NETWORK', config, {})

    const apiError = normalizeApiError(axiosError)

    expect(apiError.status).toBe(0)
    expect(apiError.code).toBe('NETWORK_ERROR')
    expect(apiError.message).toContain('后端已启动')
    expect(apiError.isNetworkError).toBe(true)
  })
})

describe('http 拦截器', () => {
  it('响应拦截器把 401 错误响应规范化为 ApiError 抛出', async () => {
    const adapter: AxiosAdapter = async (config) => {
      throw new AxiosError('Request failed with status code 401', 'ERR_BAD_REQUEST', config, {}, {
        status: 401,
        statusText: 'Unauthorized',
        headers: {},
        config,
        data: { code: 'UNAUTHORIZED', message: 'missing token', requestId: 'req-401', details: null },
      })
    }
    http.defaults.adapter = adapter

    await expect(http.get('/secure')).rejects.toMatchObject({
      name: 'ApiError',
      status: 401,
      code: 'UNAUTHORIZED',
      requestId: 'req-401',
    })
  })

  it('请求拦截器注入 X-Request-Id,字典接口完成 optionCode/optionLabel 字段映射', async () => {
    let seenHeaders: unknown
    const adapter: AxiosAdapter = async (config) => {
      seenHeaders = config.headers
      expect(config.url).toBe('/dictionaries/cities/options')
      return {
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
        data: {
          code: 'OK',
          message: 'success',
          data: [
            { optionCode: 'HZ', optionLabel: '杭州', sortNo: 1 },
            { optionCode: 'SH', optionLabel: '上海', sortNo: 2 },
          ],
          requestId: 'req-ok',
          timestamp: '2026-10-23T00:00:00Z',
        },
      }
    }
    http.defaults.adapter = adapter

    const options = await getDictionaryOptions('cities')

    expect(options).toEqual([
      { code: 'HZ', label: '杭州', sortNo: 1 },
      { code: 'SH', label: '上海', sortNo: 2 },
    ])
    const headers = seenHeaders as AxiosHeaders
    expect(headers.get('X-Request-Id')).toBeTruthy()
  })

  it('业务码非 OK 的 2xx 响应也视为失败', async () => {
    const adapter: AxiosAdapter = async (config) => ({
      status: 200,
      statusText: 'OK',
      headers: {},
      config,
      data: { code: 'ENTITLEMENT_INSUFFICIENT', message: '额度不足', requestId: 'req-409' },
    })
    http.defaults.adapter = adapter

    await expect(http.get('/quota')).rejects.toMatchObject({
      name: 'ApiError',
      status: 200,
      code: 'ENTITLEMENT_INSUFFICIENT',
      requestId: 'req-409',
    })
  })
})
