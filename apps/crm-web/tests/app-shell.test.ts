import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { Router } from 'vue-router'
import App from '../src/App.vue'
import { logout } from '../src/api/auth'
import type { AuthUser } from '../src/api/auth'
import { listAppointments } from '../src/api/fulfillment'
import { listTasks } from '../src/api/task'
import { useAuthStore } from '../src/stores/auth'

vi.mock('../src/api/auth', () => ({
  login: vi.fn(),
  logout: vi.fn(),
  fetchMe: vi.fn(),
}))
vi.mock('../src/api/task', () => ({ listTasks: vi.fn() }))
vi.mock('../src/api/fulfillment', () => ({ listAppointments: vi.fn() }))

const Page = { template: '<div>业务页面</div>' }
const LoginPage = { template: '<div>登录页</div>' }
const ALL_PERMISSIONS = [
  'customer:read',
  'customer:merge',
  'followup:read',
  'task:read',
  'user:read',
  'dictionary:read',
  'audit:read',
]
const wrappers: VueWrapper[] = []

function makeUser(roles: string[], permissions: string[], userType = 'EMPLOYEE'): AuthUser {
  return {
    userId: 1,
    username: 'tester',
    displayName: '测试账号',
    userType,
    roles,
    permissions,
  }
}

function createTestRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: Page, meta: { title: '工作台' } },
      { path: '/operations/dashboard', name: 'operations-dashboard', component: Page, meta: { display: true } },
      { path: '/customers', name: 'customers', component: Page },
      { path: '/customers/merge', name: 'customer-merge', component: Page },
      { path: '/customers/ownership-logs', name: 'ownership-logs', component: Page },
      { path: '/followups', name: 'followups', component: Page },
      { path: '/tasks', name: 'tasks', component: Page },
      { path: '/appointments', name: 'appointments', component: Page },
      { path: '/redemptions', name: 'redemptions', component: Page },
      { path: '/institutions', name: 'institutions', component: Page },
      { path: '/institutions/change-approvals', name: 'institution-changes', component: Page },
      { path: '/users', name: 'users', component: Page },
      { path: '/roles', name: 'roles', component: Page },
      { path: '/dictionaries', name: 'dictionaries', component: Page },
      { path: '/audit-logs', name: 'audit-logs', component: Page },
      { path: '/403', name: 'forbidden', component: Page },
      { path: '/login', name: 'login', component: LoginPage, meta: { public: true } },
    ],
  })
}

async function mountApp(user = makeUser(['ADMIN'], ALL_PERMISSIONS)) {
  const router = createTestRouter()
  await router.push('/')
  await router.isReady()
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore(pinia)
  auth.user = user
  auth.initialized = true
  const wrapper = mount(App, {
    attachTo: document.body,
    global: { plugins: [router, pinia, ElementPlus] },
  })
  wrappers.push(wrapper)
  await flushPromises()
  return { wrapper, router, auth }
}

beforeEach(() => {
  vi.mocked(logout).mockReset().mockResolvedValue(undefined)
  vi.mocked(listTasks).mockReset().mockResolvedValue({ items: [], total: 7, page: 1, size: 1 })
  vi.mocked(listAppointments).mockReset().mockResolvedValue({ items: [], total: 0, page: 1, size: 1 })
})

afterEach(() => {
  while (wrappers.length) wrappers.pop()?.unmount()
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})

describe('共享应用外壳', () => {
  it('渲染品牌、管理范围、真实业务内容和接口返回的任务角标', async () => {
    const { wrapper } = await mountApp()

    expect(wrapper.text()).toContain('鲸选美学')
    expect(wrapper.text()).toContain('会员体验管理 CRM')
    expect(wrapper.text()).not.toContain('MEMBER EXPERIENCE')
    expect(wrapper.text()).toContain('开发环境')
    expect(wrapper.text()).toContain('全局管理视图')
    expect(wrapper.text()).toContain('业务页面')
    expect(wrapper.find('.shell-sidebar').exists()).toBe(true)
    expect(wrapper.find('.shell-header').exists()).toBe(true)
    expect(listTasks).toHaveBeenCalledWith({ page: 1, size: 1, status: 'OPEN' })
    expect(wrapper.findAll('.nav-group-badge').some((badge) => badge.text() === '7')).toBe(true)
  })

  it('管家和机构分别使用自己的工作台文案与数据范围', async () => {
    const manager = await mountApp(
      makeUser(['MANAGER'], ['customer:read', 'followup:read', 'task:read']),
    )
    expect(manager.wrapper.text()).toContain('管家工作台')
    expect(manager.wrapper.text()).toContain('仅本人负责客户')
    expect(manager.wrapper.text()).toContain('我的客户')
    expect(manager.wrapper.text()).not.toContain('用户管理')
    manager.wrapper.unmount()

    const institution = await mountApp(makeUser(
      ['INSTITUTION_OPERATOR'],
      ['institution:read', 'appointment:read', 'redemption:read'],
      'INSTITUTION',
    ))
    expect(institution.wrapper.text()).toContain('合作机构工作台')
    expect(institution.wrapper.text()).toContain('仅本机构授权数据')
    expect(institution.wrapper.text()).toContain('机构资料')
    expect(institution.wrapper.text()).toContain('核销确认')
    expect(institution.wrapper.text()).not.toContain('客户查重合并')
  })

  it('导航分组可折叠并同步 aria-expanded', async () => {
    const { wrapper } = await mountApp()
    const toggle = wrapper.findAll('.nav-parent').find((item) => item.text().includes('客户管理'))
    expect(toggle).toBeDefined()
    expect(toggle?.attributes('aria-expanded')).toBe('false')

    await toggle?.trigger('click')
    expect(toggle?.attributes('aria-expanded')).toBe('true')
    await toggle?.trigger('click')
    expect(toggle?.attributes('aria-expanded')).toBe('false')
  })

  it('移动抽屉可打开、关闭，Escape 关闭后焦点返回菜单按钮', async () => {
    const { wrapper } = await mountApp()
    const toggle = wrapper.get('.menu-button')

    await toggle.trigger('click')
    await flushPromises()
    expect(wrapper.get('.shell-sidebar').classes()).toContain('is-open')
    expect(toggle.attributes('aria-expanded')).toBe('true')

    await wrapper.get('.sidebar-close').trigger('click')
    await flushPromises()
    expect(wrapper.get('.shell-sidebar').classes()).not.toContain('is-open')
    expect(toggle.attributes('aria-expanded')).toBe('false')

    await toggle.trigger('click')
    await flushPromises()
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await flushPromises()
    expect(wrapper.get('.shell-sidebar').classes()).not.toContain('is-open')
    expect(document.activeElement).toBe(toggle.element)
  })

  it('取消切换身份时不调用登出接口并保留会话', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel' as never)
    const { wrapper, router, auth } = await mountApp()

    await wrapper.get('.switch-login').trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalledTimes(1)
    expect(logout).not.toHaveBeenCalled()
    expect(auth.user?.username).toBe('tester')
    expect(router.currentRoute.value.name).toBe('home')
  })

  it('独立大屏路由不渲染业务侧栏和顶栏', async () => {
    const { wrapper, router } = await mountApp()

    await router.push('/operations/dashboard')
    await flushPromises()

    expect(wrapper.text()).toContain('业务页面')
    expect(wrapper.find('.shell-sidebar').exists()).toBe(false)
    expect(wrapper.find('.shell-header').exists()).toBe(false)
  })

  it('确认退出后调用真实登出接口、清空会话并进入登录页', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const { wrapper, router, auth } = await mountApp()

    await wrapper.get('.role-switch').trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalledTimes(1)
    expect(logout).toHaveBeenCalledTimes(1)
    expect(auth.user).toBeNull()
    expect(router.currentRoute.value.name).toBe('login')
    expect(wrapper.text()).toContain('登录页')
    expect(wrapper.find('.shell-sidebar').exists()).toBe(false)
  })
})
