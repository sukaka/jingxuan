import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createWecomMapping, listWecomMappings, updateWecomMapping } from '../src/api/wecomMapping'
import type { WecomMapping } from '../src/api/wecomMapping'
import { useAuthStore } from '../src/stores/auth'
import WecomMappingSettings from '../src/components/WecomMappingSettings.vue'

vi.mock('../src/api/wecomMapping', () => ({
  listWecomMappings: vi.fn(), createWecomMapping: vi.fn(), updateWecomMapping: vi.fn(),
}))

const mapping: WecomMapping = { id: 8, corpId: 'MOCK-CORP-001', wecomUserid: 'lead1', staffUserId: 4, archiveEnabled: false, messageEnabled: true, version: 0 }

function mountComponent(permissions = ['notification:delivery:read', 'notification:delivery:retry']) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = { userId: 1, username: 'admin', displayName: '管理员', userType: 'EMPLOYEE', roles: ['ADMIN'], permissions }
  auth.initialized = true
  return mount(WecomMappingSettings, { global: { plugins: [pinia] } })
}

describe('企微通知映射设置', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(listWecomMappings).mockResolvedValue([mapping])
    vi.mocked(createWecomMapping).mockResolvedValue(mapping)
    vi.mocked(updateWecomMapping).mockResolvedValue({ ...mapping, messageEnabled: false, version: 1 })
  })

  it('新增映射时提交独立通知开关、理由和稳定幂等键', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const form = wrapper.get('[data-testid="mapping-create-form"]')
    await form.get('input[aria-label="企微成员 ID"]').setValue('lead1')
    await form.get('input[aria-label="CRM 员工 ID"]').setValue('4')
    await form.trigger('submit')
    await flushPromises()
    expect(createWecomMapping).toHaveBeenCalledWith(expect.objectContaining({ corpId: 'MOCK-CORP-001', wecomUserid: 'lead1', staffUserId: 4, messageEnabled: true }), expect.any(String))
  })

  it('修改只更新通知状态并携带版本和理由，不改变归档事实', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    await wrapper.get('[aria-label="映射 8 通知开关"]').setValue(false)
    await wrapper.get('[aria-label="映射 8 修改理由"]').setValue('暂停该成员通知')
    await wrapper.get('[data-testid="mapping-8"] button').trigger('click')
    await flushPromises()
    expect(updateWecomMapping).toHaveBeenCalledWith(8, expect.objectContaining({ messageEnabled: false, version: 0, reason: '暂停该成员通知' }), expect.any(String))
  })

  it('无管理权限时不发起读取或写入', async () => {
    mountComponent([])
    await flushPromises()
    expect(listWecomMappings).not.toHaveBeenCalled()
    expect(createWecomMapping).not.toHaveBeenCalled()
  })

  it('明确标识 MOCK 企微成员 ID 是开发模拟数据', async () => {
    const wrapper = mountComponent()
    await flushPromises()

    expect(wrapper.text()).toContain('开发模拟数据')
    expect(wrapper.text()).toContain('不是员工姓名')
  })
})
