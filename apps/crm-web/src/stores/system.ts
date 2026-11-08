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
          this.healthMessage = '后端服务运行正常'
        } else {
          this.healthStatus = 'down'
          this.healthMessage = `后端健康状态异常:${health.status}`
        }
      } catch (error) {
        if (error instanceof ApiError && error.isUnauthorized) {
          this.healthStatus = 'unauthorized'
          this.healthMessage = '已连接·未认证(请重新登录)'
        } else if (error instanceof ApiError && error.isNetworkError) {
          this.healthStatus = 'unreachable'
          this.healthMessage = '无法连接后端服务,请确认后端已启动(http://localhost:8080)'
        } else {
          this.healthStatus = 'down'
          this.healthMessage = error instanceof Error ? error.message : '健康检查失败'
        }
      } finally {
        this.lastCheckedAt = new Date().toLocaleString()
      }
    },
  },
})
