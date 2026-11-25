import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuthUser } from '../src/api/auth'
import type { Channel, ChannelFinancialChangeRequest } from '../src/api/channel'
import {
  createChannelFinancialChangeRequest,
  listAllChannelFinancialChangeRequests,
  listChannels,
  reviewChannelFinancialChangeRequest,
  toggleChannelStatus,
  updateChannel,
} from '../src/api/channel'
import { useAuthStore } from '../src/stores/auth'
import ChannelsView from '../src/views/ChannelsView.vue'

vi.mock('../src/api/channel', () => ({
  createChannel: vi.fn(),
  listChannels: vi.fn(),
  updateChannel: vi.fn(),
  toggleChannelStatus: vi.fn(),
  createChannelFinancialChangeRequest: vi.fn(),
  listChannelFinancialChangeRequests: vi.fn(),
  listAllChannelFinancialChangeRequests: vi.fn(),
  reviewChannelFinancialChangeRequest: vi.fn(),
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

function mountView(customUser: AuthUser = user) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = customUser
  auth.initialized = true
  const wrapper = mount(ChannelsView, {
    global: { plugins: [ElementPlus, pinia] },
    attachTo: document.body,
  })
  wrappers.push(wrapper)
  return wrapper
}

beforeEach(() => {
  vi.mocked(listAllChannelFinancialChangeRequests).mockResolvedValue({ items: [], total: 0, page: 1, size: 20 })
})

afterEach(() => {
  while (wrappers.length) wrappers.pop()?.unmount()
  document.body.innerHTML = ''
  vi.restoreAllMocks()
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

  it('暂停渠道调用 POST /status 而不是 PATCH updateChannel', async () => {
    const activeChannel: Channel = {
      id: 91,
      channelNo: 'CH-PAUSE',
      name: '待暂停渠道',
      channelType: 'BLOGGER',
      platformCode: 'XHS',
      commissionPercent: 12.5,
      businessOwnerUserId: 8,
      businessOwnerName: '孙周',
      realName: '测试',
      contactPhone: '138****0000',
      contactPhoneConfigured: true,
      idCardNumber: '110105********002X',
      idCardConfigured: true,
      settlementMethod: 'MONTHLY',
      cooperationStartDate: '2026-01-01',
      cooperationEndDate: '2026-12-31',
      paymentAccountName: '张*',
      paymentAccountNo: '****0001',
      paymentAccountConfigured: true,
      paymentBankName: '招商银行',
      status: 'ACTIVE',
      remarks: null,
      createdAt: '2026-10-01T00:00:00Z',
      version: 4,
    }
    vi.mocked(listChannels).mockResolvedValue({ items: [activeChannel], total: 1, page: 1, size: 20 })
    vi.mocked(toggleChannelStatus).mockResolvedValue({ ...activeChannel, status: 'INACTIVE', version: 5 })
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)

    const wrapper = mountView()
    await flushPromises()
    await wrapper.get('[data-testid="channel-pause-91"]').trigger('click')
    await flushPromises()

    expect(toggleChannelStatus).toHaveBeenCalledWith(91, {
      status: 'INACTIVE',
      version: 4,
      reason: '运营操作暂停合作',
    })
    expect(updateChannel).not.toHaveBeenCalled()
  })

  it('财务审批受 channel:approve_finance 与 SoD 限制，且可提交财务变更申请', async () => {
    const channel: Channel = {
      id: 92,
      channelNo: 'CH-FIN',
      name: '调佣渠道',
      channelType: 'BLOGGER',
      platformCode: 'XHS',
      commissionPercent: 10,
      businessOwnerUserId: 8,
      businessOwnerName: '孙周',
      realName: null,
      contactPhone: null,
      contactPhoneConfigured: false,
      idCardNumber: null,
      idCardConfigured: false,
      settlementMethod: 'MONTHLY',
      cooperationStartDate: null,
      cooperationEndDate: null,
      paymentAccountName: null,
      paymentAccountNo: null,
      paymentAccountConfigured: false,
      paymentBankName: null,
      status: 'ACTIVE',
      remarks: null,
      createdAt: '2026-10-01T00:00:00Z',
      version: 2,
    }
    const selfReq: ChannelFinancialChangeRequest = {
      id: 701,
      channelId: 92,
      channelName: '调佣渠道',
      status: 'PENDING_REVIEW',
      requestedBy: 99,
      requestedAt: '2026-10-25T01:00:00Z',
      reason: '本人申请调佣',
      baseVersion: 2,
      version: 0,
      beforeMasked: { commissionPercent: 10 },
      afterMasked: { commissionPercent: 15 },
    }
    const otherReq: ChannelFinancialChangeRequest = { ...selfReq, id: 702, requestedBy: 8 }
    vi.mocked(listChannels).mockResolvedValue({ items: [channel], total: 1, page: 1, size: 20 })
    vi.mocked(listAllChannelFinancialChangeRequests).mockResolvedValue({
      items: [selfReq, otherReq],
      total: 2,
      page: 1,
      size: 20,
    })
    vi.mocked(createChannelFinancialChangeRequest).mockResolvedValue(otherReq)
    vi.mocked(reviewChannelFinancialChangeRequest).mockResolvedValue({ ...otherReq, status: 'APPROVED', version: 1 })
    vi.spyOn(ElMessageBox, 'prompt').mockResolvedValue({ value: '核对无误，同意', action: 'confirm' } as never)

    const financeUser: AuthUser = {
      userId: 99,
      username: 'finance1',
      displayName: '财务专员',
      userType: 'EMPLOYEE',
      roles: ['FINANCE'],
      permissions: ['channel:read', 'channel:write', 'channel:approve_finance'],
    }
    const wrapper = mountView(financeUser)
    await flushPromises()

    await wrapper.get('[data-testid="channel-finance-change-92"]').trigger('click')
    await flushPromises()
    const bankRoot = document.body.querySelector<HTMLElement>('[data-testid="finance-bank"]')!
    const bankInput = bankRoot.matches('input') ? bankRoot as HTMLInputElement : bankRoot.querySelector<HTMLInputElement>('input')!
    bankInput.value = '工商银行'
    bankInput.dispatchEvent(new Event('input', { bubbles: true }))
    const reasonRoot = document.body.querySelector<HTMLElement>('[data-testid="finance-reason"]')!
    const reasonInput = reasonRoot.matches('textarea') ? reasonRoot as HTMLTextAreaElement : reasonRoot.querySelector<HTMLTextAreaElement>('textarea')!
    reasonInput.value = '更换对公结算行'
    reasonInput.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()
    document.body.querySelector<HTMLElement>('[data-testid="finance-submit"]')!.click()
    await flushPromises()

    expect(createChannelFinancialChangeRequest).toHaveBeenCalledWith(
      92,
      { baseVersion: 2, paymentBankName: '工商银行', reason: '更换对公结算行' },
      expect.any(String),
    )

    // 切换到审批标签页：本人提交的 #701 不可自审，他人提交的 #702 可审批
    const tabs = wrapper.findAll('.el-tabs__item')
    await tabs[1]!.trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="channel-change-approve-701"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('本人不可自审')
    expect(wrapper.text()).toContain('分佣比例：10%')
    expect(wrapper.text()).toContain('分佣比例：15%')
    expect(wrapper.text()).not.toContain('commissionPercent')
    expect(wrapper.text()).not.toContain('channel:approve_finance')
    await wrapper.get('[data-testid="channel-change-approve-702"]').trigger('click')
    await flushPromises()

    expect(reviewChannelFinancialChangeRequest).toHaveBeenCalledWith(
      702,
      { decision: 'APPROVE', version: 0, comment: '核对无误，同意' },
      expect.any(String),
    )
  })

  it('财务审批失败后以相同参数重试时沿用同一个 Idempotency-Key', async () => {
    const req: ChannelFinancialChangeRequest = {
      id: 705,
      channelId: 92,
      channelName: '调佣渠道',
      status: 'PENDING_REVIEW',
      requestedBy: 8,
      requestedAt: '2026-10-25T01:00:00Z',
      reason: '更换收款行',
      baseVersion: 2,
      version: 0,
      beforeMasked: { paymentBankName: '旧银行' },
      afterMasked: { paymentBankName: '新银行' },
    }
    vi.mocked(listChannels).mockResolvedValue({ items: [], total: 0, page: 1, size: 20 })
    vi.mocked(listAllChannelFinancialChangeRequests).mockResolvedValue({
      items: [req],
      total: 1,
      page: 1,
      size: 20,
    })
    vi.mocked(reviewChannelFinancialChangeRequest)
      .mockRejectedValueOnce(new Error('网络超时'))
      .mockResolvedValueOnce({ ...req, status: 'APPROVED', version: 1 })
    vi.spyOn(ElMessageBox, 'prompt').mockResolvedValue({ value: '同意变更', action: 'confirm' } as never)

    const financeUser: AuthUser = {
      userId: 99,
      username: 'finance1',
      displayName: '财务专员',
      userType: 'EMPLOYEE',
      roles: ['FINANCE'],
      permissions: ['channel:read', 'channel:write', 'channel:approve_finance'],
    }
    const wrapper = mountView(financeUser)
    await flushPromises()

    const tabs = wrapper.findAll('.el-tabs__item')
    await tabs[1]!.trigger('click')
    await flushPromises()

    await wrapper.get('[data-testid="channel-change-approve-705"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="channel-change-approve-705"]').trigger('click')
    await flushPromises()

    expect(reviewChannelFinancialChangeRequest).toHaveBeenCalledTimes(2)
    const firstKey = vi.mocked(reviewChannelFinancialChangeRequest).mock.calls[0]?.[2]
    const secondKey = vi.mocked(reviewChannelFinancialChangeRequest).mock.calls[1]?.[2]
    expect(firstKey).toBeTruthy()
    expect(secondKey).toBe(firstKey)
  })
})
