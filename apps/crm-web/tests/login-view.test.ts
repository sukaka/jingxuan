import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { getCaptcha, login } from '../src/api/auth'
import type { AuthUser } from '../src/api/auth'
import { useAuthStore } from '../src/stores/auth'
import { ApiError } from '../src/types/api'
import LoginView from '../src/views/LoginView.vue'

vi.mock('../src/api/auth', () => ({
  login: vi.fn(),
  logout: vi.fn(),
  fetchMe: vi.fn(),
  getCaptcha: vi.fn(),
}))

const user: AuthUser = {
  userId: 1,
  username: 'operator',
  displayName: '运营账号',
  userType: 'EMPLOYEE',
  roles: ['ADMIN'],
  permissions: ['customer:read'],
}
const Page = { template: '<div>目标页面</div>' }

async function mountLogin(redirect = '/customers') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: Page },
      { path: '/login', component: Page },
      { path: '/customers', component: Page },
    ],
  })
  await router.push({ path: '/login', query: { redirect } })
  await router.isReady()
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore(pinia)
  const wrapper = mount(LoginView, { global: { plugins: [router, pinia, ElementPlus] } })
  return { wrapper, router, auth }
}

beforeEach(() => {
  vi.mocked(login).mockReset()
  vi.mocked(getCaptcha).mockReset().mockResolvedValue({
    captchaId: 'captcha-test',
    imageBase64: 'png-data',
    expiresIn: 300,
  })
})

describe('LoginView 真实账号登录', () => {
  it('提交真实用户名密码、写入会话并跳转到原目标路由', async () => {
    vi.mocked(login).mockResolvedValue(user)
    const { wrapper, router, auth } = await mountLogin()

    await wrapper.get('#login-username').setValue('  operator  ')
    await wrapper.get('#login-password').setValue('secret-password')
    await wrapper.get('#login-captcha').setValue('14')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(login).toHaveBeenCalledWith({
      username: 'operator',
      password: 'secret-password',
      captchaId: 'captcha-test',
      captchaAnswer: '14',
    })
    expect(wrapper.get('.login-captcha-image img').attributes('src')).toBe('data:image/png;base64,png-data')
    expect(auth.user).toEqual(user)
    expect(auth.initialized).toBe(true)
    expect(router.currentRoute.value.path).toBe('/customers')
    expect(wrapper.text()).toContain('使用系统分配的用户名和密码登录')
    expect(wrapper.find('.development-badge').text()).toBe('开发环境')
  })

  it('登录页不提供身份快捷入口，初始保持真实未登录状态', async () => {
    const { wrapper, router, auth } = await mountLogin()
    await flushPromises()

    expect(wrapper.findAll('.role-block')).toHaveLength(0)
    expect(wrapper.findAll('.role-actions button')).toHaveLength(0)
    expect(login).not.toHaveBeenCalled()
    expect(auth.user).toBeNull()
    expect(router.currentRoute.value.path).toBe('/login')
    expect((wrapper.get('#login-username').element as HTMLInputElement).value).toBe('')
    expect((wrapper.get('#login-password').element as HTMLInputElement).value).toBe('')
  })

  it('用户名或密码为空时不调用登录接口', async () => {
    const { wrapper } = await mountLogin()

    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(login).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('请输入用户名和密码')
  })

  it('401 展示后端统一错误且停留在登录页', async () => {
    vi.mocked(login).mockRejectedValue(
      new ApiError({ status: 401, code: 'UNAUTHORIZED', message: '用户名或密码错误' }),
    )
    const { wrapper, router, auth } = await mountLogin()
    await wrapper.get('#login-username').setValue('operator')
    await wrapper.get('#login-password').setValue('wrong')
    await wrapper.get('#login-captcha').setValue('14')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('用户名或密码错误')
    expect(getCaptcha).toHaveBeenCalledTimes(2)
    expect(auth.user).toBeNull()
    expect(router.currentRoute.value.path).toBe('/login')
  })

  it('后端不可达时展示连接错误，不进入目标页面', async () => {
    vi.mocked(login).mockRejectedValue(
      new ApiError({ status: 0, code: 'NETWORK_ERROR', message: '无法连接' }),
    )
    const { wrapper, router } = await mountLogin()
    await wrapper.get('#login-username').setValue('operator')
    await wrapper.get('#login-password').setValue('secret-password')
    await wrapper.get('#login-captcha').setValue('14')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('无法连接后端服务')
    expect(router.currentRoute.value.path).toBe('/login')
  })

  it('拒绝外部重定向地址，登录成功只进入本站工作台', async () => {
    vi.mocked(login).mockResolvedValue(user)
    const { wrapper, router } = await mountLogin('//external.example')
    await wrapper.get('#login-username').setValue('operator')
    await wrapper.get('#login-password').setValue('secret-password')
    await wrapper.get('#login-captcha').setValue('14')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/')
  })
})
