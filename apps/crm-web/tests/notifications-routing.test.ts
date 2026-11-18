import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { AuthUser } from '../src/api/auth'
import { fetchMe } from '../src/api/auth'
import { installAuthGuard } from '../src/router/guards'
import { routes } from '../src/router/index'
import { isMenuGroup, visibleMenuItems } from '../src/router/menu'
import { ApiError } from '../src/types/api'

vi.mock('../src/api/auth', () => ({
  login: vi.fn(),
  logout: vi.fn(),
  fetchMe: vi.fn(),
}))

const userWithoutBusinessPermissions: AuthUser = {
  userId: 7,
  username: 'notification-user',
  displayName: '通知用户',
  userType: 'EMPLOYEE',
  roles: ['MANAGER'],
  permissions: [],
}

function createTestRouter() {
  const testRouter = createRouter({ history: createMemoryHistory(), routes })
  installAuthGuard(testRouter)
  return testRouter
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.mocked(fetchMe).mockReset()
})

describe('通知中心登录与本人数据权限边界', () => {
  it('路由不是公开页，也不要求额外业务权限码', () => {
    const route = routes.find((item) => item.path === '/notifications')

    expect(route).toBeDefined()
    expect(route?.name).toBe('notifications')
    expect(route?.meta).toMatchObject({ title: '通知中心' })
    expect(route?.meta?.public).not.toBe(true)
    expect(route?.meta?.permission).toBeUndefined()
  })

  it('未登录访问通知中心仍跳转登录并保留深链', async () => {
    vi.mocked(fetchMe).mockRejectedValue(new ApiError({ status: 401, code: 'UNAUTHORIZED', message: '未认证' }))
    const testRouter = createTestRouter()

    await testRouter.push('/notifications?page=2')

    expect(testRouter.currentRoute.value.name).toBe('login')
    expect(testRouter.currentRoute.value.query.redirect).toBe('/notifications?page=2')
  })

  it('没有业务权限的登录用户仍可查看本人通知，不借前端权限码扩大范围', async () => {
    vi.mocked(fetchMe).mockResolvedValue(userWithoutBusinessPermissions)
    const testRouter = createTestRouter()

    await testRouter.push('/notifications')

    expect(testRouter.currentRoute.value.name).toBe('notifications')
  })

  it.each([
    { roles: ['ADMIN'], userType: 'EMPLOYEE' },
    { roles: ['MANAGER'], userType: 'EMPLOYEE' },
    { roles: ['INSTITUTION_OPERATOR'], userType: 'INSTITUTION' },
  ])('$roles 无业务权限也有本人通知菜单', ({ roles, userType }) => {
    const entries = visibleMenuItems(() => false, roles, userType)
    const items = entries.flatMap((entry) => isMenuGroup(entry) ? entry.children : [entry])

    expect(items.some((item) => item.path === '/notifications')).toBe(true)
    expect(items.find((item) => item.path === '/notifications')?.permission).toBeUndefined()
  })
})
