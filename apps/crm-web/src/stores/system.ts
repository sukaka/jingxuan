import { defineStore } from 'pinia'
import { getHealth } from '../api/health'
import { ApiError } from '../types/api'

export type HealthStatus =
  | 'unknown' // 尚未检查
  | 'checking' // 检查中
  | 'up' // 后端健康
  | 'unauthorized' // 已连接但未认证(401,后端安全默认拒绝)
  | 'unreachable' // 无法连接(后端未启动/网络失败)
  | 'down' // 已连接但健康检查未通过(非 UP 或其他错误)

interface SystemState {
  healthStatus: HealthStatus
  healthMessage: string
  lastCheckedAt: string | null
}

export const useSystemStore = defineStore('system', {
  state: (): SystemState => ({
    healthStatus: 'unknown',
    healthMessage: '',
    lastCheckedAt: null,
  }),
  actions: {
    async checkHealth() {
      this.healthStatus = 'checking'
      this.healthMessage = ''
      try {
        const health = await getHealth()
        if (health.status === 'UP') {
          this.healthStatus = 'up'
          this.healthMessage = '系统服务运行正常'
        } else {
          this.healthStatus = 'down'
          this.healthMessage = '系统服务状态异常，请稍后重试'
        }
      } catch (error) {
        if (error instanceof ApiError && error.isUnauthorized) {
          this.healthStatus = 'unauthorized'
          this.healthMessage = '登录状态已失效，请重新登录'
        } else if (error instanceof ApiError && error.isNetworkError) {
          this.healthStatus = 'unreachable'
          this.healthMessage = '服务暂时无法连接，请检查网络或稍后重试'
        } else {
          this.healthStatus = 'down'
          this.healthMessage = error instanceof Error ? error.message : '系统连接检查失败'
        }
      } finally {
        this.lastCheckedAt = new Date().toLocaleString()
      }
    },
  },
})
