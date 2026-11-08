import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { AuthUser } from '../src/api/auth'
import { listChannels } from '../src/api/channel'
import { useAuthStore } from '../src/stores/auth'
import ChannelsView from '../src/views/ChannelsView.vue'

vi.mock('../src/api/channel', () => ({
  createChannel: vi.fn(),
  listChannels: vi.fn(),
  updateChannel: vi.fn(),
}))

vi.mock('../src/api/dictionary', () => ({
  getDictionaryOptions: vi.fn().mockResolvedValue([{ code: 'XHS', label: '小红书', sortNo: 1 }]),
}))

const user: AuthUser = {
  userId: 8,
  username: 'test-business',
  displayName: '测试商务',
  userType: 'EMPLOYEE',
  roles: ['BUSINESS'],
  permissions: ['channel:read', 'channel:write', 'dictionary:read'],
}

const wrappers: VueWrapper[] = []

function mountView() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = user
  auth.initialized = true
  const wrapper = mount(ChannelsView, {
    global: { plugins: [ElementPlus, pinia] },
    attachTo: document.body,
  })
  wrappers.push(wrapper)
  return wrapper
}

afterEach(() => {
  while (wrappers.length) wrappers.pop()?.unmount()
  document.body.innerHTML = ''
  vi.clearAllMocks()
})

describe('ChannelsView 真实渠道主数据', () => {
  it('展示平台、商务和状态，缺少真实归因时明确提示且不填演示线索订单', async () => {
    vi.mocked(listChannels).mockResolvedValue({
      items: [{
        id: 91,
        channelNo: 'CH-TEST',
        name: '测试合作渠道',
        platformCode: 'XHS',
        businessOwnerUserId: 8,
        status: 'ACTIVE',
        remarks: null,
        createdAt: '2026-10-01T00:00:00Z',
        version: 0,
      }],
      total: 1,
      page: 1,
      size: 20,
    })
    const wrapper = mountView()
    await flushPromises()

    expect(listChannels).toHaveBeenCalledWith({ page: 1, size: 20, keyword: undefined, status: undefined })
    expect(wrapper.text()).toContain('CH-TEST')
    expect(wrapper.text()).toContain('测试合作渠道')
    expect(wrapper.text()).toContain('小红书')
    expect(wrapper.text()).toContain('用户 #8')
    expect(wrapper.text()).toContain('合作中')
    expect(wrapper.text()).toContain('缺少真实归因数据')
    expect(wrapper.text()).toContain('不按 0 计算')
    const headers = wrapper.findAll('.el-table__header-wrapper th').map((node) => node.text())
    expect(headers).not.toContain('归因线索')
    expect(headers).not.toContain('订单')
  })

  it('接口失败展示错误，不回退到原型渠道数据', async () => {
    vi.mocked(listChannels).mockRejectedValue(new Error('渠道服务不可用'))
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('渠道服务不可用')
    expect(wrapper.text()).not.toContain('博主001')
    expect(wrapper.findAll('.el-table__row')).toHaveLength(0)
    expect(wrapper.text()).toContain('暂无渠道')
  })
})
