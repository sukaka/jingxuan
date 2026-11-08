import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuthUser } from '../src/api/auth'
import { createFollowup, listFollowups } from '../src/api/followup'
import { useAuthStore } from '../src/stores/auth'
import { ApiError } from '../src/types/api'
import FollowupsView from '../src/views/FollowupsView.vue'

vi.mock('../src/api/followup', () => ({
  listFollowups: vi.fn(),
  createFollowup: vi.fn(),
  updateFollowup: vi.fn(),
}))

vi.mock('../src/api/dictionary', () => ({
  getDictionaryOptions: vi.fn().mockResolvedValue([
    { code: 'WECOM', label: '企业微信', sortNo: 1 },
    { code: 'PHONE', label: '电话', sortNo: 2 },
  ]),
}))

const managerUser: AuthUser = {
  userId: 2,
  username: 'manager1',
  displayName: '王丽',
  userType: 'EMPLOYEE',
  roles: ['MANAGER'],
  permissions: ['followup:read', 'followup:write'],
}

const samplePage = {
  items: [
    {
      id: 11,
      customerId: 1,
      customerName: '陈女士',
      ownerStaffId: 2,
      channelCode: 'WECOM',
      contactAt: '2026-10-23T10:00:00',
      summary: '沟通超声炮意向',
      intentProject: '超声炮',
      estimatedAmountFen: 128800,
      nextFollowupAt: '2026-10-25T10:00:00',
      blockerNote: null,
      createdBy: 2,
      createdAt: '2026-10-23T10:05:00',
      version: 0,
    },
  ],
  total: 1,
  page: 1,
  size: 20,
}

function mountView(user: AuthUser) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = user
  auth.initialized = true
  return mount(FollowupsView, { global: { plugins: [ElementPlus, pinia] } })
}

describe('FollowupsView 每日跟进台账', () => {
  beforeEach(() => {
    vi.mocked(listFollowups).mockReset()
    vi.mocked(createFollowup).mockReset()
  })

  it('列表渲染沟通方式字典名与预计金额(分→元)', async () => {
    vi.mocked(listFollowups).mockResolvedValue(samplePage)
    const wrapper = mountView(managerUser)
    await flushPromises()

    expect(wrapper.text()).toContain('沟通超声炮意向')
    expect(wrapper.text()).toContain('企业微信')
    expect(wrapper.text()).toContain('预计 ¥1288.00')
    expect(wrapper.text()).not.toContain('预计 ¥128800')
  })

  it('加载失败展示错误态', async () => {
    vi.mocked(listFollowups).mockRejectedValue(
      new ApiError({ status: 403, code: 'FORBIDDEN', message: '无跟进查看权限' }),
    )
    const wrapper = mountView(managerUser)
    await flushPromises()

    expect(wrapper.text()).toContain('加载失败')
    expect(wrapper.text()).toContain('无跟进查看权限')
  })

  it('登记表单缺少客户 ID/沟通方式时不发请求', async () => {
    vi.mocked(listFollowups).mockResolvedValue({ items: [], total: 0, page: 1, size: 20 })
    const wrapper = mountView(managerUser)
    await flushPromises()

    const openButton = wrapper.findAll('button').find((b) => b.text().includes('新增沟通记录'))
    expect(openButton).toBeDefined()
    await openButton?.trigger('click')
    await flushPromises()
    const submitButton = wrapper.findAll('button').find((b) => b.text() === '登记')
    expect(submitButton).toBeDefined()
    await submitButton?.trigger('click')
    await flushPromises()

    expect(createFollowup).not.toHaveBeenCalled()
  })
})
