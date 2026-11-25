import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuthUser } from '../src/api/auth'
import { changePassword, fetchMe, login, logout } from '../src/api/auth'
import { useAuthStore } from '../src/stores/auth'
import { ApiError } from '../src/types/api'
import { resetReferenceNames } from '../src/views/references'

vi.mock('../src/api/auth', () => ({
  login: vi.fn(),
  logout: vi.fn(),
  fetchMe: vi.fn(),
  changePassword: vi.fn(),
}))

vi.mock('../src/views/references', () => ({
  resetReferenceNames: vi.fn(),
}))

const adminUser: AuthUser = {
  userId: 1,
  username: 'admin',
  displayName: '管理员',
  userType: 'EMPLOYEE',
  roles: ['ADMIN'],
  permissions: ['user:read', 'user:write', 'dictionary:read', 'audit:read'],
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.mocked(login).mockReset()
  vi.mocked(logout).mockReset()
  vi.mocked(fetchMe).mockReset()
  vi.mocked(changePassword).mockReset()
  vi.mocked(resetReferenceNames).mockReset()
})

describe('auth store - 登录', () => {
  it('登录成功后保存用户主体,权限/角色判断生效', async () => {
    vi.mocked(login).mockResolvedValue(adminUser)
    const auth = useAuthStore()

    await auth.login('admin', 'Dev@12345', 'captcha-test', '14')

    expect(login).toHaveBeenCalledWith({
      username: 'admin',
      password: 'Dev@12345',
      captchaId: 'captcha-test',
      captchaAnswer: '14',
    })
    expect(auth.isLoggedIn).toBe(true)
    expect(auth.user?.displayName).toBe('管理员')
    expect(auth.initialized).toBe(true)
    expect(auth.hasPermission('user:read')).toBe(true)
    expect(auth.hasPermission('order:write')).toBe(false)
    expect(auth.hasRole('ADMIN')).toBe(true)
    expect(auth.hasRole('MANAGER')).toBe(false)
    expect(resetReferenceNames).toHaveBeenCalledTimes(1)
  })

  it('仅在传入非空 tenantCode 时附带租户编码', async () => {
    vi.mocked(login).mockResolvedValue(adminUser)
    const auth = useAuthStore()

    await auth.login('admin', 'Dev@12345', 'captcha-test', '14', '  tenant-b  ')

    expect(login).toHaveBeenCalledWith({
      username: 'admin',
      password: 'Dev@12345',
      captchaId: 'captcha-test',
      captchaAnswer: '14',
      tenantCode: 'tenant-b',
    })
  })

  it('机构角色按机构与角色代码校验，不使用全局角色或用户名替代', async () => {
    vi.mocked(login).mockResolvedValue({
      ...adminUser,
      institutionRoles: { 91: 'OPERATOR' },
    })
    const auth = useAuthStore()
    await auth.login('institution-user', 'Dev@12345', 'captcha-test', '14')

    expect(auth.hasInstitutionRole('OPERATOR', 91)).toBe(true)
    expect(auth.hasInstitutionRole('OPERATOR', 92)).toBe(false)
    expect(auth.hasInstitutionRole('FRONTDESK', 91)).toBe(false)
  })

  it('登录失败(401 用户名或密码错误)原样抛出,不写入用户状态', async () => {
    vi.mocked(login).mockRejectedValue(
      new ApiError({ status: 401, code: 'UNAUTHORIZED', message: '用户名或密码错误' }),
    )
    const auth = useAuthStore()

    await expect(auth.login('admin', 'wrong', 'captcha-test', '14')).rejects.toMatchObject({
      status: 401,
      code: 'UNAUTHORIZED',
      message: '用户名或密码错误',
    })
    expect(auth.isLoggedIn).toBe(false)
    expect(auth.user).toBeNull()
  })
})

describe('auth store - 登出', () => {
  it('登出调用后端接口并清空本地用户状态', async () => {
    vi.mocked(login).mockResolvedValue(adminUser)
    vi.mocked(logout).mockResolvedValue(undefined)
    const auth = useAuthStore()
    await auth.login('admin', 'Dev@12345', 'captcha-test', '14')

    await auth.logout()

    expect(logout).toHaveBeenCalledTimes(1)
    expect(auth.user).toBeNull()
    expect(auth.isLoggedIn).toBe(false)
    expect(resetReferenceNames).toHaveBeenCalledTimes(2)
  })

  it('后端不可达时登出仍清空本地状态', async () => {
    vi.mocked(login).mockResolvedValue(adminUser)
    vi.mocked(logout).mockRejectedValue(
      new ApiError({ status: 0, code: 'NETWORK_ERROR', message: '无法连接后端服务' }),
    )
    const auth = useAuthStore()
    await auth.login('admin', 'Dev@12345', 'captcha-test', '14')

    await expect(auth.logout()).rejects.toBeInstanceOf(ApiError)
    expect(auth.user).toBeNull()
  })
})

describe('auth store - 会话恢复(fetchMe)', () => {
  it('Cookie 有效时恢复登录主体', async () => {
    vi.mocked(fetchMe).mockResolvedValue(adminUser)
    const auth = useAuthStore()

    await auth.fetchMe()

    expect(auth.isLoggedIn).toBe(true)
    expect(auth.initialized).toBe(true)
  })

  it('401 时视为未登录并标记已初始化(不重复请求)', async () => {
    vi.mocked(fetchMe).mockRejectedValue(
      new ApiError({ status: 401, code: 'UNAUTHORIZED', message: '未认证或凭证已失效' }),
    )
    const auth = useAuthStore()

    await auth.fetchMe()

    expect(auth.user).toBeNull()
    expect(auth.isLoggedIn).toBe(false)
    expect(auth.initialized).toBe(true)
    expect(resetReferenceNames).toHaveBeenCalledTimes(1)
  })

  it('网络失败同样视为未登录,不抛给路由守卫', async () => {
    vi.mocked(fetchMe).mockRejectedValue(
      new ApiError({ status: 0, code: 'NETWORK_ERROR', message: '无法连接后端服务' }),
    )
    const auth = useAuthStore()

    await expect(auth.fetchMe()).resolves.toBeUndefined()
    expect(auth.isLoggedIn).toBe(false)
    expect(auth.initialized).toBe(true)
  })
})

describe('auth store - 修改密码', () => {
  it('修改密码成功后清空本地会话和引用缓存', async () => {
    vi.mocked(login).mockResolvedValue(adminUser)
    vi.mocked(changePassword).mockResolvedValue(undefined)
    const auth = useAuthStore()
    await auth.login('admin', 'Dev@12345', 'captcha-test', '14')

    await auth.changePassword({
      currentPassword: 'Dev@12345',
      newPassword: 'NewStrong#2026',
      confirmPassword: 'NewStrong#2026',
    })

    expect(changePassword).toHaveBeenCalledTimes(1)
    expect(auth.user).toBeNull()
    expect(resetReferenceNames).toHaveBeenCalledTimes(2)
  })

  it('原密码校验失败时原样抛出且保留当前会话', async () => {
    vi.mocked(login).mockResolvedValue(adminUser)
    vi.mocked(changePassword).mockRejectedValue(
      new ApiError({ status: 400, code: 'VALIDATION_FAILED', message: '原密码不正确' }),
    )
    const auth = useAuthStore()
    await auth.login('admin', 'Dev@12345', 'captcha-test', '14')

    await expect(auth.changePassword({
      currentPassword: 'Wrong@123',
      newPassword: 'NewStrong#2026',
      confirmPassword: 'NewStrong#2026',
    })).rejects.toMatchObject({ status: 400, code: 'VALIDATION_FAILED' })
    expect(auth.user?.username).toBe('admin')
  })
})
