import axios, { AxiosError } from 'axios'
import type { ApiErrorBody, ApiResponse } from '../types/api'
import { ApiError } from '../types/api'
import { getCookie } from '../utils/cookie'
import { createRequestId } from '../utils/requestId'

/** 后端通过 Set-Cookie 下发 HttpOnly JWT(CRM_TOKEN),浏览器同源请求自动携带,前端不接触 token 明文 */
export const CSRF_COOKIE_NAME = 'CRM_CSRF'
export const CSRF_HEADER_NAME = 'X-CSRF-Token'

const WRITE_METHODS = new Set(['post', 'put', 'patch', 'delete'])

/** 登录接口本身不要求 CSRF(尚无会话),其余写方法必须携带 X-CSRF-Token 且等于 CRM_CSRF Cookie */
const CSRF_EXEMPT_PATHS = new Set(['/auth/login'])

/** 统一 axios 实例,后端 API 前缀 /api/v1(dev 下由 vite 代理到 http://localhost:8080) */
export const http = axios.create({
  baseURL: '/api/v1',
  timeout: 10000,
})

http.interceptors.request.use((config) => {
  // 每个请求注入链路追踪 ID,与后端 requestId 对齐
  config.headers['X-Request-Id'] = createRequestId()
  const method = (config.method ?? 'get').toLowerCase()
  const path = (config.url ?? '').split('?')[0] ?? ''
  if (WRITE_METHODS.has(method) && !CSRF_EXEMPT_PATHS.has(path)) {
    const csrfToken = getCookie(CSRF_COOKIE_NAME)
    if (csrfToken) {
      config.headers[CSRF_HEADER_NAME] = csrfToken
    }
  }
  return config
})

/**
 * 把任意 axios 错误规范化为 ApiError:
 * - 有响应:取后端错误体的 code/message/requestId/details,401 统一给出"未认证"语义
 * - 无响应:网络/后端未启动,status 归一为 0
 */
export function normalizeApiError(error: unknown): ApiError {
  if (axios.isAxiosError(error)) {
    const axErr = error as AxiosError<ApiErrorBody>
    if (axErr.response) {
      const status = axErr.response.status
      const body: ApiErrorBody =
        axErr.response.data && typeof axErr.response.data === 'object' ? axErr.response.data : {}
      // 后端未启动时 dev 代理返回 5xx HTML 错误页(非后端 JSON 错误体),归类为无法连接
      if (status >= 500 && !body.code) {
        return new ApiError({
          status: 0,
          code: 'NETWORK_ERROR',
          message: '无法连接后端服务,请确认后端已启动(http://localhost:8080)',
        })
      }
      const code = body.code ?? (status === 401 ? 'UNAUTHORIZED' : `HTTP_${status}`)
      const message =
        status === 401
          ? (body.message ?? '未认证或会话已失效,请重新登录')
          : (body.message ?? `请求失败(HTTP ${status})`)
      return new ApiError({
        status,
        code,
        message,
        requestId: body.requestId,
        details: body.details,
      })
    }
    if (axErr.request) {
      return new ApiError({
        status: 0,
        code: 'NETWORK_ERROR',
        message: '无法连接后端服务,请确认后端已启动(http://localhost:8080)',
      })
    }
  }
  return new ApiError({
    status: -1,
    code: 'UNKNOWN',
    message: error instanceof Error ? error.message : '未知错误',
  })
}

http.interceptors.response.use(
  (response) => {
    const body = response.data as ApiResponse | undefined
    if (body && typeof body === 'object' && 'code' in body && body.code !== 'OK') {
      // HTTP 2xx 但业务码非 OK,同样视为失败
      throw new ApiError({
        status: response.status,
        code: body.code,
        message: body.message,
        requestId: body.requestId,
      })
    }
    return response
  },
  (error: unknown) => Promise.reject(normalizeApiError(error)),
)
