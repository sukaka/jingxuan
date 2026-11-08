import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuthUser } from '../src/api/auth'
import type { CustomerDetail } from '../src/api/customer'
import { getCustomer, updateCustomer } from '../src/api/customer'
import { getDictionaryOptions } from '../src/api/dictionary'
import { useAuthStore } from '../src/stores/auth'
import CustomerFormDrawer from '../src/views/customer/CustomerFormDrawer.vue'
import { useDictionaries } from '../src/views/customer/shared'

vi.mock('../src/api/customer', () => ({
  createCustomer: vi.fn(),
  getCustomer: vi.fn(),
  listCustomerOwnerOptions: vi.fn().mockResolvedValue([]),
  updateCustomer: vi.fn(),
}))

vi.mock('../src/api/dictionary', () => ({
  getDictionaryOptions: vi.fn(),
}))

const user: AuthUser = {
  userId: 2,
  username: 'test-manager',
  displayName: '测试管家',
  userType: 'EMPLOYEE',
  roles: ['MANAGER'],
  permissions: ['customer:read', 'customer:write', 'dictionary:read'],
}

const detail: CustomerDetail = {
  id: 42,
  customerNo: 'C-TEST-42',
  displayName: '测试客户',
  nickname: 'D01测试',
  mobileMasked: null,
  cityCode: 'D01CITY',
  customerStatus: 'ACTIVE',
  tierCode: 'S',
  intentLevel: 'HIGH',
  ownerStaffId: 2,
  ownerName: '测试管家',
  teamId: 1,
  pointsCached: 0,
  lastFollowupAt: null,
  createdAt: '2026-10-01T00:00:00Z',
  version: 3,
  sourceCode: null,
  channelCode: null,
  spendingLevel: null,
  birthday: null,
  attentionProjects: null,
  blockerNote: null,
  remark: null,
  remarkName: null,
  firstAddedAt: null,
  firstOrderAt: null,
  tagCodes: [],
  identities: [],
  deleted: false,
}

const wrappers: VueWrapper[] = []

function piniaWithUser() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = user
  auth.initialized = true
  return pinia
}

beforeEach(() => {
  vi.mocked(getDictionaryOptions).mockImplementation(async (dictCode) => {
    if (dictCode === 'cities') return [{ code: 'BJ', label: '北京', sortNo: 1 }]
    return []
  })
  vi.mocked(getCustomer).mockResolvedValue(detail)
  vi.mocked(updateCustomer).mockResolvedValue(detail)
})

afterEach(() => {
  while (wrappers.length) wrappers.pop()?.unmount()
  document.body.innerHTML = ''
  vi.clearAllMocks()
})

describe('D01 字典历史值与停用边界', () => {
  it('启用项可新选，历史码只读回显且不编造创建时旧显示名', async () => {
    const dicts = useDictionaries()
    await dicts.load('cities')

    expect(dicts.label('cities', 'BJ')).toBe('北京')
    expect(dicts.label('cities', 'D01CITY')).toBe('D01CITY（历史/停用）')
    expect(dicts.label('cities', 'D01CITY')).not.toContain('创建时城市')
    const options = dicts.optionsWithHistory('cities', 'D01CITY')
    expect(options.find((option) => option.code === 'BJ')?.disabled).toBe(false)
    expect(options.find((option) => option.code === 'D01CITY')).toMatchObject({ disabled: true })
    expect(dicts.optionsWithHistory('cities', '')).toHaveLength(1)
  })

  it('客户编辑保留历史 cityCode，停用项不可重新选择，普通保存不静默清除', async () => {
    const wrapper = mount(CustomerFormDrawer, {
      props: { modelValue: false, mode: 'edit', customerId: 42 },
      global: {
        plugins: [ElementPlus, piniaWithUser()],
        stubs: { 'el-drawer': { template: '<div><slot /><slot name="footer" /></div>' } },
      },
      attachTo: document.body,
    })
    wrappers.push(wrapper)
    await wrapper.setProps({ modelValue: true })
    await flushPromises()

    const options = wrapper.findAllComponents({ name: 'ElOption' })
    const historical = options.find((option) => option.props('value') === 'D01CITY')
    const active = options.find((option) => option.props('value') === 'BJ')
    expect(historical?.props('disabled')).toBe(true)
    expect(historical?.props('label')).toBe('D01CITY（历史/停用）')
    expect(active?.props('disabled')).toBe(false)

    const save = wrapper.findAll('button').find((button) => button.text() === '保存')
    expect(save).toBeDefined()
    await save?.trigger('click')
    await flushPromises()
    expect(updateCustomer).toHaveBeenCalledWith(42, expect.objectContaining({ cityCode: 'D01CITY', version: 3 }))
  })
})
