/**
 * 与后端约定的统一响应结构。
 * 成功:{"code":"OK","message":"success","data":...,"requestId":"...","timestamp":"..."}
 * 失败:{"code":"ENTITLEMENT_INSUFFICIENT","message":"...","details":...,"requestId":"..."}
 */
export interface ApiResponse<T = unknown> {
  code: string
  message: string
  data: T
  requestId: string
  timestamp: string
}

/** 统一分页列表结构(P0-1 钉死:列表返回 items/total/page/size,size<=100) */
export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  size: number
}

/** 错误响应体(HTTP 状态:400/401/403/404/409/422/429/5xx) */
export interface ApiErrorBody {
  code?: string
  message?: string
  details?: unknown
  requestId?: string
}

export interface ApiErrorInit {
  status: number
  code: string
  message: string
  requestId?: string | undefined
  details?: unknown
}

/** 规范化后的前端错误对象,所有 API 调用抛出的都是它 */
export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly requestId: string | undefined
  readonly details: unknown

  constructor(init: ApiErrorInit) {
    super(init.message)
    this.name = 'ApiError'
    this.status = init.status
    this.code = init.code
    this.requestId = init.requestId
    this.details = init.details
  }

  /** 未认证(后端安全默认拒绝时业务接口统一返回 401) */
  get isUnauthorized(): boolean {
    return this.status === 401
  }

  /** 越权 */
  get isForbidden(): boolean {
    return this.status === 403
  }

  /** 网络层失败,通常是后端未启动 */
  get isNetworkError(): boolean {
    return this.status === 0
  }
}
