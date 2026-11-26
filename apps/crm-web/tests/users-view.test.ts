import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createUser, listUsers, listUserSupervisors, listUserTeams, updateUser } from '../src/api/users'
import { listRoles } from '../src/api/roles'
import { useAuthStore } from '../src/stores/auth'
import { ApiError } from '../src/types/api'
import { extractApiFieldErrors, formatApiError, validateCreateUserInput } from '../src/utils/userValidation'
import UsersView from '../src/views/UsersView.vue'

vi.mock('../src/api/users', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../src/api/users')>()),
  createUser: vi.fn(),
  listUsers: vi.fn(),
  listUserSupervisors: vi.fn(),
  listUserTeams: vi.fn(),
  updateUser: vi.fn(),
}))

vi.mock('../src/api/roles', () => ({
  listRoles: vi.fn(),
}))

const wrappers: VueWrapper[] = []

const sampleUser = {
  id: 2,
  username: 'manager1',
  displayName: '王丽',
  userType: 'EMPLOYEE',
  status: 'ACTIVE',
  teamId: 1,
  teamName: '管家一组',
  roles: ['MANAGER'],
  lastLoginAt: null,
  createdAt: '2026-10-23T09:00:00+08:00',
  version: 0,
}

function mountView() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = {
    userId: 1,
    username: 'admin',
    displayName: '系统管理员',
    userType: 'EMPLOYEE',
    roles: ['ADMIN'],
    permissions: ['user:read', 'user:write'],
  }
  auth.initialized = true
  const wrapper = mount(UsersView, {
    attachTo: document.body,
    global: {
      plugins: [ElementPlus, pinia],
      stubs: { RouterLink: { template: '<a><slot /></a>' } },
    },
  })
  wrappers.push(wrapper)
  return wrapper
}

afterEach(() => {
  while (wrappers.length) wrappers.pop()?.unmount()
  document.body.innerHTML = ''
})

describe('UsersView 多维组合筛选', () => {
  beforeEach(() => {
    localStorage.removeItem('crm:table:v1:1:user-management')
    vi.mocked(createUser).mockReset().mockResolvedValue({ ...sampleUser, id: 9, username: 'ranran', displayName: '魏' })
    vi.mocked(updateUser).mockReset().mockResolvedValue({ ...sampleUser, version: 1 })
    vi.mocked(listUsers).mockReset().mockResolvedValue({ items: [sampleUser], total: 1, page: 1, size: 20 })
    vi.mocked(listUserTeams).mockReset().mockResolvedValue([
      { id: 1, teamName: '管家一组' },
      { id: 2, teamName: '管家二组' },
    ])
    vi.mocked(listUserSupervisors).mockReset().mockResolvedValue([
      { id: 4, username: 'lead1', displayName: '赵主管', teamId: 1, teamName: '管家一组' },
    ])
    vi.mocked(listRoles).mockReset().mockResolvedValue([
      { id: 1, roleCode: 'ADMIN', roleName: '系统管理员', status: 'ACTIVE', permissions: [], version: 0 },
      { id: 2, roleCode: 'MANAGER', roleName: '管家', status: 'ACTIVE', permissions: [], version: 0 },
    ])
  })

  it('默认全部维度不限，组合条件查询后重置恢复全部', async () => {
    const wrapper = mountView()
    await flushPromises()

    const fields = wrapper.findAll('.filter-field')
    expect(fields).toHaveLength(5)
    expect(fields.map((field) => field.find('span').text())).toEqual([
      '关键词', '用户类型', '所属团队', '角色', '状态',
    ])
    const selects = wrapper.findAllComponents({ name: 'ElSelect' }).filter((select) => select.element.closest('.filter-field'))
    expect(selects).toHaveLength(4)
    expect(selects.map((select) => select.props('modelValue'))).toEqual(['', '', '', ''])
    expect(wrapper.text()).toContain('全部')

    vi.mocked(listUsers).mockClear()
    await wrapper.get('.filter-keyword input').setValue('王')
    await selects[0]!.vm.$emit('update:modelValue', 'EMPLOYEE')
    await selects[1]!.vm.$emit('update:modelValue', 1)
    await selects[2]!.vm.$emit('update:modelValue', 'MANAGER')
    await selects[3]!.vm.$emit('update:modelValue', 'ACTIVE')
    await flushPromises()
    await wrapper.get('.filter-search').trigger('click')
    await flushPromises()

    expect(listUsers).toHaveBeenCalledWith({
      page: 1,
      size: 20,
      keyword: '王',
      status: 'ACTIVE',
      userType: 'EMPLOYEE',
      teamId: 1,
      roleCode: 'MANAGER',
    })
    expect(wrapper.text()).toContain('已选条件')

    await wrapper.get('.filter-reset').trigger('click')
    await flushPromises()
    expect(listUsers).toHaveBeenLastCalledWith({
      page: 1,
      size: 20,
      keyword: '',
      status: '',
      userType: '',
      teamId: '',
      roleCode: '',
    })
    expect(wrapper.text()).not.toContain('已选条件')
  })

  it('普通列可伸展填满表格，并可隐藏和恢复指定列', async () => {
    const wrapper = mountView()
    await flushPromises()
    const usernameColumn = wrapper.findAllComponents({ name: 'ElTableColumn' })
      .find((column) => column.props('label') === '用户名')
    expect(Number(usernameColumn?.props('minWidth'))).toBe(140)

    const usernameSetting = wrapper.findAllComponents({ name: 'ElCheckbox' })
      .find((checkbox) => checkbox.text() === '用户名')
    expect(usernameSetting).toBeDefined()
    await usernameSetting!.get('input').setValue(false)
    await flushPromises()
    expect(wrapper.get('.el-table__header-wrapper').text()).not.toContain('用户名')
    expect(wrapper.get('.el-table__body-wrapper').text()).not.toContain('manager1')

    await wrapper.findAllComponents({ name: 'ElButton' })
      .find((button) => button.text() === '恢复默认')!.trigger('click')
    await flushPromises()
    expect(wrapper.get('.el-table__header-wrapper').text()).toContain('用户名')
    expect(wrapper.get('.el-table__body-wrapper').text()).toContain('manager1')
  })
})

describe('UsersView 新建/编辑表单校验与字段错误展示', () => {
  beforeEach(() => {
    localStorage.removeItem('crm:table:v1:1:user-management')
    vi.mocked(createUser).mockReset().mockResolvedValue({ ...sampleUser, id: 9, username: 'ranran', displayName: '魏' })
    vi.mocked(updateUser).mockReset().mockResolvedValue({ ...sampleUser, version: 1 })
    vi.mocked(listUsers).mockReset().mockResolvedValue({ items: [sampleUser], total: 1, page: 1, size: 20 })
    vi.mocked(listUserTeams).mockReset().mockResolvedValue([
      { id: 1, teamName: '管家一组' },
    ])
    vi.mocked(listUserSupervisors).mockReset().mockResolvedValue([
      { id: 4, username: 'lead1', displayName: '赵主管', teamId: 1, teamName: '管家一组' },
    ])
    vi.mocked(listRoles).mockReset().mockResolvedValue([
      { id: 2, roleCode: 'MANAGER', roleName: '管家', status: 'ACTIVE', permissions: [], version: 0 },
    ])
  })

  it('校验函数覆盖中文用户名、短密码及后端 details 汇总', () => {
    const check = validateCreateUserInput({
      username: '冉冉',
      displayName: '魏',
      password: '123456',
      userType: 'EMPLOYEE',
    })
    expect(check.valid).toBe(false)
    expect(check.fieldErrors.username).toContain('3-32 位')
    expect(check.fieldErrors.password).toContain('8-64 位')

    const apiError = new ApiError({
      status: 400,
      code: 'VALIDATION_FAILED',
      message: '请求参数校验失败',
      details: [
        { field: 'username', reason: '登录名需为 3-32 位字母/数字/._-' },
        { field: 'password', reason: '密码长度须为 8-64 位' },
      ],
    })
    expect(extractApiFieldErrors(apiError)).toEqual({
      username: '登录名需为 3-32 位字母/数字/._-',
      password: '密码长度须为 8-64 位',
    })
    expect(formatApiError(apiError)).toBe('登录名需为 3-32 位字母/数字/._-；密码长度须为 8-64 位')
  })

  it('新建用户拦截中文用户名和 6 位密码并展示行内提示，修正后可正常提交', async () => {
    const wrapper = mountView()
    await flushPromises()

    await wrapper.findAllComponents({ name: 'ElButton' })
      .find((button) => button.text().includes('新建用户'))!.trigger('click')
    await flushPromises()

    expect(document.body.textContent).toContain('仅支持 3-32 位英文字母、数字及 . _ -')
    expect(document.body.textContent).toContain('密码长度须为 8-64 位')

    const usernameInput = document.querySelector<HTMLInputElement>('[data-testid="create-username-input"]')!
    const displayNameInput = document.querySelector<HTMLInputElement>('[data-testid="create-display-name-input"]')!
    const passwordInput = document.querySelector<HTMLInputElement>('[data-testid="create-password-input"]')!
    const submitBtn = document.querySelector<HTMLButtonElement>('[data-testid="create-user-submit"]')!

    usernameInput.value = '冉冉'
    usernameInput.dispatchEvent(new Event('input'))
    displayNameInput.value = '魏'
    displayNameInput.dispatchEvent(new Event('input'))
    passwordInput.value = '123456'
    passwordInput.dispatchEvent(new Event('input'))
    await flushPromises()

    submitBtn.click()
    await flushPromises()

    expect(createUser).not.toHaveBeenCalled()
    expect(document.querySelector('[data-testid="create-username-error"]')?.textContent).toContain('不支持中文')
    expect(document.querySelector('[data-testid="create-password-error"]')?.textContent).toContain('8-64 位')

    usernameInput.value = 'wei_ranran'
    usernameInput.dispatchEvent(new Event('input'))
    passwordInput.value = 'Pass@1234'
    passwordInput.dispatchEvent(new Event('input'))
    await flushPromises()

    expect(document.querySelector('[data-testid="create-username-error"]')).toBeNull()
    expect(document.querySelector('[data-testid="create-password-error"]')).toBeNull()

    submitBtn.click()
    await flushPromises()

    expect(createUser).toHaveBeenCalledWith({
      username: 'wei_ranran',
      displayName: '魏',
      password: 'Pass@1234',
      userType: 'EMPLOYEE',
      teamId: null,
      supervisorUserId: null,
      roleIds: [],
    })
  })

  it('后端返回 VALIDATION_FAILED details 或 409 登录名已存在时回填到对应字段', async () => {
    vi.mocked(createUser)
      .mockRejectedValueOnce(new ApiError({
        status: 400,
        code: 'VALIDATION_FAILED',
        message: '请求参数校验失败',
        details: [{ field: 'teamId', reason: '团队不存在' }],
      }))
      .mockRejectedValueOnce(new ApiError({
        status: 409,
        code: 'CONFLICT',
        message: '登录名已存在',
      }))

    const wrapper = mountView()
    await flushPromises()

    await wrapper.findAllComponents({ name: 'ElButton' })
      .find((button) => button.text().includes('新建用户'))!.trigger('click')
    await flushPromises()

    const usernameInput = document.querySelector<HTMLInputElement>('[data-testid="create-username-input"]')!
    const displayNameInput = document.querySelector<HTMLInputElement>('[data-testid="create-display-name-input"]')!
    const passwordInput = document.querySelector<HTMLInputElement>('[data-testid="create-password-input"]')!
    const submitBtn = document.querySelector<HTMLButtonElement>('[data-testid="create-user-submit"]')!

    usernameInput.value = 'wei_ranran'
    usernameInput.dispatchEvent(new Event('input'))
    displayNameInput.value = '魏'
    displayNameInput.dispatchEvent(new Event('input'))
    passwordInput.value = 'Pass@1234'
    passwordInput.dispatchEvent(new Event('input'))
    await flushPromises()

    submitBtn.click()
    await flushPromises()

    expect(document.querySelector('[data-testid="create-team-error"]')?.textContent).toContain('团队不存在')
    expect(document.querySelector('[data-testid="create-user-submit-error"]')).toBeNull()

    submitBtn.click()
    await flushPromises()

    expect(document.querySelector('[data-testid="create-username-error"]')?.textContent).toContain('登录名已存在')
    expect(document.body.textContent).not.toContain('该记录已被他人修改')
  })

  it('编辑用户拦截空白姓名并在服务端冲突时展示明确错误', async () => {
    vi.mocked(updateUser).mockRejectedValueOnce(new ApiError({
      status: 409,
      code: 'CONFLICT',
      message: '数据已被他人修改，请刷新后重试',
    }))

    const wrapper = mountView()
    await flushPromises()

    await wrapper.findAllComponents({ name: 'ElButton' })
      .find((button) => button.text() === '编辑')!.trigger('click')
    await flushPromises()

    const displayNameInput = document.querySelector<HTMLInputElement>('[data-testid="edit-display-name-input"]')!
    const submitBtn = document.querySelector<HTMLButtonElement>('[data-testid="edit-user-submit"]')!

    displayNameInput.value = '   '
    displayNameInput.dispatchEvent(new Event('input'))
    await flushPromises()

    submitBtn.click()
    await flushPromises()

    expect(updateUser).not.toHaveBeenCalled()
    expect(document.querySelector('[data-testid="edit-display-name-error"]')?.textContent).toContain('姓名不能为空')

    displayNameInput.value = '王丽新'
    displayNameInput.dispatchEvent(new Event('input'))
    await flushPromises()

    submitBtn.click()
    await flushPromises()

    expect(updateUser).toHaveBeenCalledWith(2, {
      displayName: '王丽新',
      teamId: 1,
      supervisorUserId: null,
      version: 0,
    })
    expect(document.querySelector('[data-testid="edit-user-submit-error"]')?.textContent)
      .toContain('数据已被他人修改，请刷新后重试')
  })
})
