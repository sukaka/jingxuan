import axios from 'axios'
import { normalizeApiError } from './http'

/** Spring Actuator 健康检查响应,status 一般为 "UP" */
export interface HealthResponse {
  status: string
  components?: Record<string, { status: string }>
}

/**
 * 健康检查不走 /api/v1,直接请求 /actuator/health(dev 下同样由 vite 代理)。
 * 失败时抛出与业务接口一致的规范化 ApiError(401 = 已连接但未认证,status 0 = 无法连接)。
 */
export async function getHealth(): Promise<HealthResponse> {
  try {
    const res = await axios.get<HealthResponse>('/actuator/health', { timeout: 5000 })
    return res.data
  } catch (error) {
    throw normalizeApiError(error)
  }
}
