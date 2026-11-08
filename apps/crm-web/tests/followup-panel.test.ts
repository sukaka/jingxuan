import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuthUser } from '../src/api/auth'
import { listFollowups } from '../src/api/followup'
import { useAuthStore } from '../src/stores/auth'
import FollowupPanel from '../src/views/customer/FollowupPanel.vue'

vi.mock('../src/api/followup', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../src/api/followup')>()),
  listFollowups: vi.fn(),
}))

vi.mock('../src/api/dictionary', () => ({
  getDictionaryOptions: vi.fn().mockResolvedValue([]),
}))

const managerUser: AuthUser = {
  userId: 2,
  username: 'manager1',
  displayName: '王丽',
  userType: 'EMPLOYEE',
  roles: ['MANAGER'],
  permissions: ['followup:read', 'followup:write', 'dictionary:read'],
}

function mountPanel(user: AuthUser = managerUser) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = user
  auth.initialized = true
  return mount(FollowupPanel, {
    props: { fixedCustomerId: 1001 },
    global: {
      plugins: [ElementPlus, pinia],
      stubs: {
        FollowupFormDialog: {
          props: ['modelValue'],
          template: '<div v-if="modelValue" data-test="followup-dialog" />',
        },
      },
    },
  })
}

describe('FollowupPanel 服务记录工具栏', () => {
  beforeEach(() => {
    vi.mocked(listFollowups).mockReset().mockResolvedValue({ items: [], total: 0, page: 1, size: 20 })
  })

  it('查询为次级按钮，登记跟进为主按钮，并保持原有点击功能', async () => {
    const wrapper = mountPanel()
    await flushPromises()

    const actions = wrapper.find('.followup-actions')
    expect(actions.exists()).toBe(true)
    expect(actions.findAll('button').map((button) => button.text())).toEqual(['查询', '登记跟进'])
    const searchButton = wrapper.get('.followup-search-button')
    const createButton = wrapper.get('.followup-create-button')
    expect(searchButton.classes()).not.toContain('el-button--primary')
    expect(createButton.classes()).toContain('el-button--primary')
    expect(searchButton.find('svg').exists()).toBe(true)
    expect(createButton.find('svg').exists()).toBe(true)

    vi.mocked(listFollowups).mockClear()
    await searchButton.trigger('click')
    await flushPromises()
    expect(listFollowups).toHaveBeenCalledWith({ page: 1, size: 20, customerId: 1001 })

    await createButton.trigger('click')
    expect(wrapper.find('[data-test="followup-dialog"]').exists()).toBe(true)
  })

  it('无写入权限时隐藏登记按钮，保留查询', async () => {
    const wrapper = mountPanel({ ...managerUser, permissions: ['followup:read', 'dictionary:read'] })
    await flushPromises()

    expect(wrapper.find('.followup-search-button').exists()).toBe(true)
    expect(wrapper.find('.followup-create-button').exists()).toBe(false)
  })
})
