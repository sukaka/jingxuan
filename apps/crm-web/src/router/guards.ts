import type { Router, RouteLocationNormalized } from 'vue-router'
import { useAuthStore } from '../stores/auth'

declare module 'vue-router' {
  interface RouteMeta {
    /** 页面标题 */
    title?: string
    /** 顶栏面包屑分组 */
    section?: string
    /** 无需登录(如登录页) */
    public?: boolean
    /** 进入该路由所需的权限码;数组表示任一权限即可,缺省表示登录即可 */
    permission?: string | string[]
    /** 独立展示页面，不渲染业务外壳，但仍执行登录与权限守卫 */
    display?: boolean
  }
}

/**
 * 全局前置守卫:
 * 1. public 路由直接放行;已登录访问登录页时回首页
 * 2. 首次导航先 fetchMe 依据 HttpOnly Cookie 恢复会话
 * 3. 未登录访问受保护路由 → /login?redirect=<原路径>
 * 4. 缺少路由声明的权限码 → /403(菜单隐藏不等于授权,后端仍会二次校验)
 */
export function installAuthGuard(router: Router): void {
  router.beforeEach(async (to: RouteLocationNormalized) => {
    const auth = useAuthStore()

    if (to.meta.public) {
      if (to.name === 'login' && auth.isLoggedIn) {
        return { name: 'home' }
      }
      return true
    }

    if (!auth.initialized) {
      await auth.fetchMe()
    }
    if (!auth.isLoggedIn) {
      return { name: 'login', query: { redirect: to.fullPath } }
    }
    const requiredPermissions = to.meta.permission
    if (requiredPermissions) {
      const allowed = Array.isArray(requiredPermissions)
        ? requiredPermissions.some((permission) => auth.hasPermission(permission))
        : auth.hasPermission(requiredPermissions)
      if (!allowed) return { name: 'forbidden' }
    }
    return true
  })
}
