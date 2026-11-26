import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { CustomerDetail } from '../src/api/customer'
import { getCustomer, updateCustomer } from '../src/api/customer'
import { listProjects } from '../src/api/catalog'
import CustomerFormDrawer from '../src/views/customer/CustomerFormDrawer.vue'
import { useAuthStore } from '../src/stores/auth'

vi.mock('../src/api/customer', () => ({
  createCustomer: vi.fn(), getCustomer: vi.fn(), listCustomerOwnerOptions: vi.fn().mockResolvedValue([]),
  updateCustomer: vi.fn(), listCustomerTags: vi.fn().mockResolvedValue([]),
  createCustomerTag: vi.fn(), updateCustomerTag: vi.fn(), disableCustomerTag: vi.fn(), enableCustomerTag: vi.fn(),
  listCustomerSources: vi.fn().mockResolvedValue([]), createCustomerSource: vi.fn(), updateCustomerSource: vi.fn(),
  disableCustomerSource: vi.fn(), enableCustomerSource: vi.fn(),
  listCustomerChannels: vi.fn().mockResolvedValue([]), createCustomerChannel: vi.fn(), updateCustomerChannel: vi.fn(),
  disableCustomerChannel: vi.fn(), enableCustomerChannel: vi.fn(),
}))
vi.mock('../src/api/catalog', () => ({ listProjects: vi.fn() }))
vi.mock('../src/api/dictionary', () => ({ getDictionaryOptions: vi.fn().mockResolvedValue([]) }))

const detail: CustomerDetail = {
  id: 42, customerNo: 'C42', displayName: null, nickname: 'V33客户', mobileMasked: null,
  cityCode: null, customerStatus: null, tierCode: 'A', intentLevel: null, ownerStaffId: 2,
  ownerName: null, teamId: 1, pointsCached: 0, lastFollowupAt: null, createdAt: '', version: 3,
  sourceCode: null, channelCode: null, spendingLevel: null, birthday: null,
  attentionProjects: '不能自动匹配的旧文本', attentionProjectIds: [101], blockerNote: null,
  remark: null, remarkName: null, firstAddedAt: null, firstOrderAt: null, tagCodes: [], identities: [],
  deleted: false,
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(getCustomer).mockResolvedValue(detail)
  vi.mocked(updateCustomer).mockResolvedValue(detail)
  vi.mocked(listProjects).mockResolvedValue({
    items: [
      { id: 101, projectCode: 'P101', projectName: '基础水光', categoryCode: null,
        listPriceFen: 100, status: 'ACTIVE', createdAt: '', version: 0 },
      { id: 102, projectCode: 'P102', projectName: '超光子', categoryCode: null,
        listPriceFen: 100, status: 'ACTIVE', createdAt: '', version: 0 },
    ],
    total: 2, page: 1, size: 100,
  })
})

afterEach(() => {
  document.body.innerHTML = ''
})

describe('CustomerFormDrawer 正规关注项目', () => {
  it('保留 legacy 文本但只用 projectIds 多选保存，不上送旧文本猜测映射', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    auth.user = {
      userId: 2, username: 'manager1', displayName: '管家', userType: 'EMPLOYEE',
      roles: ['MANAGER'], permissions: ['customer:read', 'customer:write', 'product:read'],
    }
    const wrapper = mount(CustomerFormDrawer, {
      props: { modelValue: false, mode: 'edit', customerId: 42 },
      global: {
        plugins: [ElementPlus, pinia],
        stubs: { 'el-drawer': { template: '<div><slot /><slot name="footer" /></div>' } },
      },
      attachTo: document.body,
    })
    await wrapper.setProps({ modelValue: true })
    await flushPromises()

    const select = wrapper.get('[data-testid="attention-project-select"]')
    expect(select.text()).toContain('基础水光')
    expect(wrapper.text()).toContain('不能自动匹配的旧文本')
    const save = wrapper.findAll('button').find((button) => button.text() === '保存')
    await save?.trigger('click')
    await flushPromises()

    expect(updateCustomer).toHaveBeenCalledWith(42, expect.objectContaining({
      projectIds: [101], version: 3,
    }))
    const request = vi.mocked(updateCustomer).mock.calls[0]?.[1]
    if (!request) throw new Error('未捕获客户更新请求')
    expect(request).not.toHaveProperty('attentionProjects')
    expect(request).not.toHaveProperty('tierCode')
  })
})
