import { AxiosError } from 'axios'
import type { AxiosAdapter, InternalAxiosRequestConfig } from 'axios'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { Router } from 'vue-router'
import type { AuthUser } from '../src/api/auth'
import { fetchMe } from '../src/api/auth'
import { http, setUnauthorizedHandler } from '../src/api/http'
import { installAuthGuard } from '../src/router/guards'
import { routes } from '../src/router/index'
import { useAuthStore } from '../src/stores/auth'
import { ApiError } from '../src/types/api'

vi.mock('../src/api/auth', () => ({
  login: vi.fn(),
  logout: vi.fn(),
  fetchMe: vi.fn(),
}))

const managerUser: AuthUser = {
  userId: 3,
  username: 'manager1',
  displayName: '王丽',
  userType: 'EMPLOYEE',
  roles: ['MANAGER'],
  permissions: ['customer:read', 'customer:write', 'dictionary:read'],
}

function createTestRouter(): Router {
  const testRouter = createRouter({ history: createMemoryHistory(), routes })
  installAuthGuard(testRouter)
  return testRouter
}

const originalAdapter = http.defaults.adapter

beforeEach(() => {
  setActivePinia(createPinia())
  vi.mocked(fetchMe).mockReset()
})

afterEach(() => {
  http.defaults.adapter = originalAdapter
  setUnauthorizedHandler(null)
})

describe('路由守卫', () => {
  it('未登录访问受保护路由 → 重定向 /login 并携带 redirect', async () => {
    vi.mocked(fetchMe).mockRejectedValue(
      new ApiError({ status: 401, code: 'UNAUTHORIZED', message: '未认证' }),
    )
    const testRouter = createTestRouter()

    await testRouter.push('/users')

    expect(testRouter.currentRoute.value.name).toBe('login')
    expect(testRouter.currentRoute.value.query.redirect).toBe('/users')
  })

  it('登录页为 public,未登录可直接访问', async () => {
    vi.mocked(fetchMe).mockRejectedValue(
      new ApiError({ status: 401, code: 'UNAUTHORIZED', message: '未认证' }),
    )
    const testRouter = createTestRouter()

    await testRouter.push('/login')

    expect(testRouter.currentRoute.value.name).toBe('login')
    expect(fetchMe).not.toHaveBeenCalled()
  })

  it('已登录但缺少页面权限(user:read) → /403', async () => {
    // MANAGER 角色无 user:read,菜单隐藏不等于授权,直输 URL 同样被拦截
    vi.mocked(fetchMe).mockResolvedValue(managerUser)
    const testRouter = createTestRouter()

    await testRouter.push('/users')

    expect(testRouter.currentRoute.value.name).toBe('forbidden')
  })

  it('已登录且具备权限 → 正常进入页面', async () => {
    vi.mocked(fetchMe).mockResolvedValue(managerUser)
    const testRouter = createTestRouter()

    await testRouter.push('/dictionaries')

    expect(testRouter.currentRoute.value.name).toBe('dictionaries')
  })

  it('已登录访问登录页 → 回工作台', async () => {
    vi.mocked(fetchMe).mockResolvedValue(managerUser)
    const testRouter = createTestRouter()
    await testRouter.push('/')

    await testRouter.push('/login')

    expect(testRouter.currentRoute.value.name).toBe('home')
  })

  it('会话过期收到业务 401 时清理登录态并单次跳转至 /login', async () => {
    vi.mocked(fetchMe).mockResolvedValue(managerUser)
    const testRouter = createTestRouter()
    await testRouter.push('/dictionaries')
    const auth = useAuthStore()
    expect(auth.isLoggedIn).toBe(true)

    const adapter: AxiosAdapter = async (config: InternalAxiosRequestConfig) => {
      throw new AxiosError('Unauthorized', 'ERR_BAD_REQUEST', config, {}, {
        status: 401,
        statusText: 'Unauthorized',
        headers: {},
        config,
        data: { code: 'UNAUTHORIZED', message: '会话过期' },
      })
    }
    http.defaults.adapter = adapter

    await expect(http.get('/customers')).rejects.toBeInstanceOf(ApiError)
    await new Promise((resolve) => setTimeout(resolve, 10))

    expect(auth.isLoggedIn).toBe(false)
    expect(testRouter.currentRoute.value.name).toBe('login')
    expect(testRouter.currentRoute.value.query.redirect).toBe('/dictionaries')
  })
})

describe('P0-1b 路由契约', () => {
  it.each([
    ['/customers', 'customer:read'],
    ['/customers/merge', 'customer:merge'],
    ['/followups', 'followup:read'],
    ['/tasks', 'task:read'],
    ['/products', 'product:read'],
    ['/skus', 'product:read'],
    ['/users', 'user:read'],
    ['/roles', 'user:read'],
    ['/dictionaries', 'dictionary:read'],
    ['/audit-logs', 'audit:read'],
  ])('保留 %s 及权限元数据', (path, permission) => {
    const route = routes.find((item) => item.path === path)
    expect(route).toBeDefined()
    expect(route?.meta?.permission).toBe(permission)
    expect(route?.component).toBeTypeOf('function')
  })
})

describe('P2 导航路由契约', () => {
  it.each([
    ['/operations', 'operations', '运营数据中心', '数据经营', 'report:read'],
    ['/performance', 'performance', '管家 / 商务业绩', '数据经营', 'report:read'],
    ['/campaigns', 'campaigns', '活动 / 大促分析', '产品运营', 'campaign:read'],
    ['/materials', 'materials', '素材库', '产品运营', 'material:read'],
    ['/exports', 'exports', '数据导出中心', '数据经营', 'export:read'],
    ['/risks', 'risks', '异常订单 / 风控台账', '交易履约', 'risk:read'],
    ['/channels', 'channels', '合作渠道', '机构合作', 'channel:read'],
  ])('%s 接入真实页面并保留中文标题、分组和读取权限', (path, name, title, section, permission) => {
    const route = routes.find((item) => item.path === path)

    expect(route).toBeDefined()
    expect(route?.name).toBe(name)
    expect(route?.meta).toMatchObject({ title, section, permission })
    expect(route?.component).toBeTypeOf('function')
  })

  it.each([
    ['/operations', 'operations', 'report:read'],
    ['/performance', 'performance', 'report:read'],
    ['/campaigns', 'campaigns', 'campaign:read'],
    ['/materials', 'materials', 'material:read'],
    ['/exports', 'exports', 'export:read'],
    ['/risks', 'risks', 'risk:read'],
    ['/channels', 'channels', 'channel:read'],
  ])('具备读权限可进入 %s，路由守卫不把菜单当作授权来源', async (path, name, permission) => {
    vi.mocked(fetchMe).mockResolvedValue({ ...managerUser, roles: ['ADMIN'], permissions: [permission] })
    const testRouter = createTestRouter()

    await testRouter.push(path)

    expect(testRouter.currentRoute.value.name).toBe(name)
  })

  it('独立大屏仍要求登录和 report:read，display 不会变成公开路由', async () => {
    const route = routes.find((item) => item.path === '/operations/dashboard')
    expect(route?.meta).toMatchObject({ permission: 'report:read', display: true })
    expect(route?.meta?.public).not.toBe(true)
    vi.mocked(fetchMe).mockResolvedValue({ ...managerUser, roles: ['ADMIN'], permissions: ['customer:read'] })
    const testRouter = createTestRouter()

    await testRouter.push('/operations/dashboard')

    expect(testRouter.currentRoute.value.name).toBe('forbidden')
  })

  it.each([
    ['/campaigns', 'campaign:write'],
    ['/materials', 'material:write'],
    ['/exports', 'export:create'],
    ['/risks', 'risk:write'],
    ['/channels', 'channel:write'],
  ])('%s 的写或创建权限不代替读取权限', async (path, permission) => {
    vi.mocked(fetchMe).mockResolvedValue({ ...managerUser, roles: ['ADMIN'], permissions: [permission] })
    const testRouter = createTestRouter()

    await testRouter.push(path)

    expect(testRouter.currentRoute.value.name).toBe('forbidden')
  })
})

describe('V30-V33 页面路由与后端权限契约', () => {
  const pageRoutes = [
    ['/settings/customer-tiers', 'customer-tier-settings', '分级规则', '系统管理', 'customer:write'],
    ['/settings/followup-automation', 'followup-automation-settings', '跟进提醒', '系统管理', 'followup:read'],
    ['/settings/complaint-automation', 'complaint-automation-settings', '客诉关键词', '系统管理', 'complaint:config'],
    ['/customers/segments', 'customer-segments', '客户分组', '客户管理', 'customer:read'],
    ['/followups/monthly-dispatch', 'monthly-dispatch', '月度派单', '客户管理', 'followup:read'],
    ['/customers/tier-reviews', 'customer-tier-reviews', '等级复核', '客户管理', 'customer:read'],
    ['/complaints', 'complaints', '客诉', '客户管理', 'complaint:read'],
    ['/customers/batch-tag-jobs', 'batch-tag-jobs', '批量标签', '客户管理', 'customer:read'],
  ] as const

  it.each(pageRoutes)('%s 注册真实页面及读取权限', (path, name, title, section, permission) => {
    const route = routes.find((item) => item.path === path)

    expect(route).toBeDefined()
    expect(route?.name).toBe(name)
    expect(route?.meta).toMatchObject({ title, section, permission })
    expect(route?.component).toBeTypeOf('function')
  })

  it.each(pageRoutes)('具备后端要求的读取权限可进入 %s', async (path, name, _title, _section, permission) => {
    const roles = path === '/settings/customer-tiers' ? ['ADMIN'] : ['MANAGER_LEAD']
    vi.mocked(fetchMe).mockResolvedValue({ ...managerUser, roles, permissions: [permission] })
    const testRouter = createTestRouter()

    await testRouter.push(path)

    expect(testRouter.currentRoute.value.name).toBe(name)
  })

  it.each([
    ['/settings/customer-tiers', 'customer:read'],
    ['/settings/followup-automation', 'followup:write'],
    ['/settings/complaint-automation', 'complaint:write'],
    ['/customers/segments', 'customer:write'],
    ['/followups/monthly-dispatch', 'followup:write'],
    ['/customers/tier-reviews', 'customer:write'],
    ['/complaints', 'complaint:write'],
    ['/customers/batch-tag-jobs', 'customer:write'],
  ])('%s 不以其他写权限代替页面读取权限', async (path, permission) => {
    vi.mocked(fetchMe).mockResolvedValue({ ...managerUser, roles: ['ADMIN'], permissions: [permission] })
    const testRouter = createTestRouter()

    await testRouter.push(path)

    expect(testRouter.currentRoute.value.name).toBe('forbidden')
  })

  it('分级规则同时要求 ADMIN，主管仅有 customer:write 也不能直输地址进入', async () => {
    vi.mocked(fetchMe).mockResolvedValue({ ...managerUser, roles: ['MANAGER_LEAD'], permissions: ['customer:write'] })
    const testRouter = createTestRouter()

    await testRouter.push('/settings/customer-tiers')

    expect(testRouter.currentRoute.value.name).toBe('forbidden')
  })
})
