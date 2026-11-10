import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { AuthUser } from '../src/api/auth'
import type { Channel } from '../src/api/channel'
import { listChannels } from '../src/api/channel'
import { useAuthStore } from '../src/stores/auth'
import ChannelsView from '../src/views/ChannelsView.vue'

vi.mock('../src/api/channel', () => ({
  createChannel: vi.fn(),
  listChannels: vi.fn(),
  updateChannel: vi.fn(),
  listChannelOwnerOptions: vi.fn().mockResolvedValue([{ id: 8, displayName: '孙周' }]),
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
        channelType: 'BLOGGER',
        platformCode: 'XHS',
        commissionPercent: 12.5,
        businessOwnerUserId: 8,
        businessOwnerName: '孙周',
        realName: '测试合作人',
        contactPhone: '13800000000',
        idCardNumber: '11010519491231002X',
        settlementMethod: 'MONTHLY',
        cooperationStartDate: '2026-01-01',
        cooperationEndDate: '2026-12-31',
        paymentAccountName: '测试收款账户',
        paymentAccountNo: '0000000000000001',
        paymentBankName: '测试银行',
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

    expect(listChannels).toHaveBeenCalledWith({ page: 1, size: 20, keyword: undefined, status: undefined, channelType: undefined })
    expect(wrapper.text()).toContain('CH-TEST')
    expect(wrapper.text()).toContain('测试合作渠道')
    expect(wrapper.text()).toContain('小红书')
    expect(wrapper.text()).toContain('孙周')
    expect(wrapper.text()).not.toContain('用户 #8')
    expect(wrapper.text()).toContain('合作中')
    expect(wrapper.text()).toContain('138****0000')
    expect(wrapper.text()).toContain('110105********002X')
    expect(wrapper.text()).toContain('**** **** **** 0001')
    expect(wrapper.text()).not.toContain('13800000000')
    expect(wrapper.text()).not.toContain('11010519491231002X')
    expect(wrapper.text()).not.toContain('0000000000000001')
    expect(wrapper.text()).toContain('缺少真实归因数据')
    expect(wrapper.text()).toContain('不按 0 计算')
    const headers = wrapper.findAll('.el-table__header-wrapper th').map((node) => node.text())
    expect(headers).not.toContain('归因线索')
    expect(headers).not.toContain('订单')
  })

  it('三种合作类型使用不同颜色标签', async () => {
    const base: Channel = {
      id: 100,
      channelNo: 'CH-COLOR',
      name: '颜色测试渠道',
      channelType: 'BLOGGER',
      platformCode: null,
      commissionPercent: null,
      businessOwnerUserId: null,
      businessOwnerName: null,
      realName: null,
      contactPhone: null,
      idCardNumber: null,
      settlementMethod: null,
      cooperationStartDate: null,
      cooperationEndDate: null,
      paymentAccountName: null,
      paymentAccountNo: null,
      paymentBankName: null,
      status: 'ACTIVE',
      remarks: null,
      createdAt: '2026-01-01T00:00:00Z',
      version: 0,
    }
    const types = ['BLOGGER', 'DISTRIBUTION', 'REFERRAL'] as const
    vi.mocked(listChannels).mockResolvedValue({
      items: types.map((channelType, index) => ({ ...base, id: base.id + index, channelType })),
      total: 3,
      page: 1,
      size: 20,
    })
    const wrapper = mountView()
    await flushPromises()

    const expected = { '博主/达人': 'teal', 分销: 'purple', 老带新: 'orange' }
    for (const [label, tone] of Object.entries(expected)) {
      const pill = wrapper.findAll('.proto-pill').find((node) => node.text() === label)
      expect(pill, label).not.toBeUndefined()
      expect(pill!.classes()).toContain(tone)
    }
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
