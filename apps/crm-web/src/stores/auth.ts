import { defineStore } from 'pinia'
import type { AuthUser } from '../api/auth'
import { fetchMe, login as loginApi, logout as logoutApi } from '../api/auth'
import { ApiError } from '../types/api'

interface AuthState {
  /** 当前登录主体;null = 未登录。token 在 HttpOnly Cookie 中,前端只保存 /auth/me 解析结果 */
  user: AuthUser | null
  /** 是否已尝试过会话恢复(fetchMe),避免路由守卫重复请求 */
  initialized: boolean
}

export const useAuthStore = defineStore('auth', {
  state: (): AuthState => ({
    user: null,
    initialized: false,
  }),
  getters: {
    isLoggedIn: (state) => state.user !== null,
  },
  actions: {
    /** 权限判断只认 /auth/me 实时返回的权限码;菜单隐藏不等于授权,后端仍会强制校验 */
    hasPermission(code: string): boolean {
      return this.user?.permissions.includes(code) ?? false
    },
    hasRole(code: string): boolean {
      return this.user?.roles.includes(code) ?? false
    },
    hasInstitutionRole(code: string, institutionId: number): boolean {
      return this.user?.institutionRoles?.[institutionId] === code
    },
    /** 登录成功由后端设置 Cookie;失败(401 用户名或密码错误)原样抛出 ApiError 给页面展示 */
    async login(username: string, password: string, captchaId: string, captchaAnswer: string) {
      this.user = await loginApi({ username, password, captchaId, captchaAnswer })
      this.initialized = true
    },
    /** 登出:通知后端清 Cookie/吊销会话(写审计),本地状态无论成功与否都清空 */
    async logout() {
      try {
        await logoutApi()
      } finally {
        this.user = null
      }
    },
    /** 刷新页面后依据 HttpOnly Cookie 恢复会话;401/网络失败均视为未登录 */
    async fetchMe() {
      try {
        this.user = await fetchMe()
      } catch (error) {
        if (error instanceof ApiError) {
          this.user = null
        } else {
          throw error
        }
      } finally {
        this.initialized = true
      }
    },
  },
})
