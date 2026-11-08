import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { listUsers, listUserTeams } from '../src/api/users'
import { listRoles } from '../src/api/roles'
import { useAuthStore } from '../src/stores/auth'
import UsersView from '../src/views/UsersView.vue'

vi.mock('../src/api/users', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../src/api/users')>()),
  listUsers: vi.fn(),
  listUserTeams: vi.fn(),
}))

vi.mock('../src/api/roles', () => ({
  listRoles: vi.fn(),
}))

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
  return mount(UsersView, {
    global: {
      plugins: [ElementPlus, pinia],
      stubs: { RouterLink: { template: '<a><slot /></a>' } },
    },
  })
}

describe('UsersView 多维组合筛选', () => {
  beforeEach(() => {
    localStorage.removeItem('crm:table:v1:1:user-management')
    vi.mocked(listUsers).mockReset().mockResolvedValue({ items: [sampleUser], total: 1, page: 1, size: 20 })
    vi.mocked(listUserTeams).mockReset().mockResolvedValue([
      { id: 1, teamName: '管家一组' },
      { id: 2, teamName: '管家二组' },
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
    wrapper.unmount()
  })
})
