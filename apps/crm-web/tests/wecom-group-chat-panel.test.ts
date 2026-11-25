import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getCustomer } from '../src/api/customer'
import { getCustomerTimeline } from '../src/api/customerTimeline'
import { listEntitlements } from '../src/api/trading'
import type { WecomSidebarGroupContext } from '../src/api/wecomSidebar'
import WecomGroupChatPanel from '../src/components/WecomGroupChatPanel.vue'

vi.mock('../src/api/customer', () => ({ getCustomer: vi.fn() }))
vi.mock('../src/api/trading', () => ({ listEntitlements: vi.fn() }))
vi.mock('../src/api/customerTimeline', () => ({ getCustomerTimeline: vi.fn() }))

vi.mock('../src/stores/auth', () => ({
  useAuthStore: () => ({
    hasPermission: () => true,
  }),
}))

const groupContext: WecomSidebarGroupContext = {
  contextStatus: 'READY',
  corpId: 'ww-test',
  chatId: 'wr-group-01',
  employee: { userId: 2, displayName: '张管家' },
  room: {
    roomId: 'wr-group-01',
    displayName: 'VIP 美学服务群',
    status: 'ACTIVE',
    staffCount: 2,
    externalCount: 3,
    linkedCustomerCount: 2,
  },
  customers: [
    {
      customerId: 1010,
      customerNo: 'CU1001',
      displayName: '王女士',
      nickname: '王小美',
      externalUserid: 'ext-1001',
      tierCode: 'S',
      ownerName: '张管家',
      mobileMasked: '138****1234',
    },
    {
      customerId: 2020,
      customerNo: 'CU2020',
      displayName: '李女士',
      nickname: '李同学',
      externalUserid: 'ext-2020',
      tierCode: 'A',
      ownerName: '张管家',
      mobileMasked: '139****5678',
    },
  ],
}

const wrappers: VueWrapper[] = []

describe('WecomGroupChatPanel 企微群聊助手', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(getCustomer).mockResolvedValue({
      id: 1010,
      customerNo: 'CU1001',
      displayName: '王女士',
      nickname: '王小美',
      mobileMasked: '138****1234',
      cityCode: '310000',
      customerStatus: 'ACTIVE',
      tierCode: 'S',
      intentLevel: 'HIGH',
      ownerStaffId: 2,
      ownerName: '张管家',
      teamId: 1,
      pointsCached: 0,
      lastFollowupAt: '2026-11-12T08:00:00Z',
      createdAt: '2026-01-01T00:00:00Z',
      version: 1,
      sourceCode: 'WECOM',
      channelCode: 'PRIVATE',
      spendingLevel: null,
      birthday: null,
      attentionProjects: null,
      attentionProjectIds: [],
      blockerNote: null,
      remark: null,
      remarkName: null,
      firstAddedAt: '2026-01-01T00:00:00Z',
      firstOrderAt: '2026-02-01T00:00:00Z',
      tagCodes: ['VIP'],
      identities: [],
      deleted: false,
    })
    vi.mocked(listEntitlements).mockResolvedValue({
      items: [{
        id: 9,
        customerId: 1010,
        orderItemId: 8,
        projectId: 7,
        projectName: '水光焕肤护理',
        grantedCount: 5,
        availableCount: 3,
        versionNo: 1,
        validFrom: '2026-01-01',
        validTo: '2027-01-01',
        status: 'ACTIVE',
        createdAt: '2026-01-01T00:00:00Z',
        version: 1,
      }],
      total: 1,
      page: 1,
      size: 50,
    })
    vi.mocked(getCustomerTimeline).mockResolvedValue({
      items: [{
        type: 'FOLLOWUP',
        eventKey: 'f-1',
        sourceId: '1',
        occurredAt: '2026-11-12T08:00:00Z',
        title: '群内术后关怀',
        summary: '反馈良好',
        details: {},
        actorUserId: 2,
      }],
      nextCursor: null,
    })
  })

  afterEach(() => {
    while (wrappers.length) wrappers.pop()?.unmount()
  })

  it('展示群信息与已关联客户，点击客户可查看档案、套餐和跟进时间线', async () => {
    const wrapper = mount(WecomGroupChatPanel, {
      props: {
        groupContext,
        chatId: 'wr-group-01',
        chatEntry: 'group_chat_tools',
      },
      global: {
        stubs: {
          WecomQuickActionsPanel: { template: '<div data-testid="quick-actions-stub" />' },
          WecomQuickReplyPanel: { template: '<div data-testid="group-qr-stub" />' },
          WecomH5CardPanel: { template: '<div data-testid="group-h5-stub" />' },
          WecomMaterialPanel: { template: '<div data-testid="group-mat-stub" />' },
        },
      },
    })
    wrappers.push(wrapper)

    expect(wrapper.text()).toContain('VIP 美学服务群')
    expect(wrapper.text()).toContain('已关联 CRM 2')
    expect(wrapper.text()).toContain('王女士')

    await wrapper.get('[data-testid="group-member-1010"]').trigger('click')
    await flushPromises()

    expect(getCustomer).toHaveBeenCalledWith(1010)
    expect(wrapper.get('[data-testid="group-member-profile-panel"]').text()).toContain('高意向')

    await wrapper.get('[data-testid="group-member-packages-tab"]').trigger('click')
    expect(wrapper.get('[data-testid="group-member-packages-panel"]').text()).toContain('水光焕肤护理')

    await wrapper.get('[data-testid="group-member-timeline-tab"]').trigger('click')
    expect(wrapper.get('[data-testid="group-member-timeline-panel"]').text()).toContain('群内术后关怀')

    await wrapper.get('[data-testid="group-member-back"]').trigger('click')
    expect(wrapper.find('[data-testid="group-member-1010"]').exists()).toBe(true)
  })

  it('支持切换群发工具（快捷话术 / H5 卡片 / 素材库）并透传 chatId 与 context-mismatch', async () => {
    const wrapper = mount(WecomGroupChatPanel, {
      props: {
        groupContext,
        chatId: 'wr-group-01',
        chatEntry: 'group_chat_tools',
      },
      global: {
        stubs: {
          WecomQuickActionsPanel: true,
          WecomQuickReplyPanel: {
            props: ['chatId', 'chatEntry', 'customerId'],
            emits: ['context-mismatch'],
            template: '<div data-testid="group-qr-stub" :data-chat="chatId" :data-entry="chatEntry"><button data-testid="trigger-mismatch" @click="$emit(\'context-mismatch\')" /></div>',
          },
          WecomH5CardPanel: {
            props: ['chatId', 'chatEntry', 'customerId'],
            template: '<div data-testid="group-h5-stub" :data-customer="customerId ?? \'\'" />',
          },
          WecomMaterialPanel: {
            props: ['chatId', 'chatEntry'],
            template: '<div data-testid="group-mat-stub" :data-chat="chatId" />',
          },
        },
      },
    })
    wrappers.push(wrapper)

    await wrapper.get('[data-testid="group-tools-tab"]').trigger('click')
    expect(wrapper.get('[data-testid="group-qr-stub"]').attributes('data-chat')).toBe('wr-group-01')
    expect(wrapper.get('[data-testid="group-qr-stub"]').attributes('data-entry')).toBe('group_chat_tools')

    await wrapper.get('[data-testid="trigger-mismatch"]').trigger('click')
    expect(wrapper.emitted('context-mismatch')).toHaveLength(1)

    await wrapper.get('[data-testid="group-tool-customer-select"]').setValue('1010')
    await wrapper.get('[data-testid="group-h5-card-tab"]').trigger('click')
    expect(wrapper.get('[data-testid="group-h5-stub"]').attributes('data-customer')).toBe('1010')

    await wrapper.get('[data-testid="group-material-tab"]').trigger('click')
    expect(wrapper.get('[data-testid="group-mat-stub"]').attributes('data-chat')).toBe('wr-group-01')
  })

  it('在群发工具切换或清空参考客户时同步刷新/清空客户详情，防止串客展示或误改', async () => {
    vi.mocked(getCustomer).mockImplementation(async (id: number) => ({
      id,
      customerNo: id === 1010 ? 'CU1001' : 'CU2020',
      displayName: id === 1010 ? '王女士' : '李女士',
      nickname: null,
      mobileMasked: id === 1010 ? '138****1234' : '139****5678',
      cityCode: '310000',
      customerStatus: 'ACTIVE',
      tierCode: id === 1010 ? 'S' : 'A',
      intentLevel: id === 1010 ? 'HIGH' : 'LOW',
      ownerStaffId: 2,
      ownerName: '张管家',
      teamId: 1,
      pointsCached: 0,
      lastFollowupAt: '2026-11-12T08:00:00Z',
      createdAt: '2026-01-01T00:00:00Z',
      version: 1,
      sourceCode: 'WECOM',
      channelCode: 'PRIVATE',
      spendingLevel: null,
      birthday: null,
      attentionProjects: null,
      attentionProjectIds: [],
      blockerNote: null,
      remark: null,
      remarkName: null,
      firstAddedAt: '2026-01-01T00:00:00Z',
      firstOrderAt: '2026-02-01T00:00:00Z',
      tagCodes: id === 1010 ? ['VIP'] : ['NEW_7D'],
      identities: [],
      deleted: false,
    }))
    vi.mocked(listEntitlements).mockImplementation(async ({ customerId }) => ({
      items: [{
        id: customerId === 1010 ? 9 : 19,
        customerId: customerId ?? 0,
        orderItemId: 8,
        projectId: 7,
        projectName: customerId === 1010 ? '水光焕肤护理' : '热玛吉紧致方案',
        grantedCount: 5,
        availableCount: 3,
        versionNo: 1,
        validFrom: '2026-01-01',
        validTo: '2027-01-01',
        status: 'ACTIVE',
        createdAt: '2026-01-01T00:00:00Z',
        version: 1,
      }],
      total: 1,
      page: 1,
      size: 50,
    }))
    vi.mocked(getCustomerTimeline).mockImplementation(async (customerId: number) => ({
      items: [{
        type: 'FOLLOWUP',
        eventKey: `f-${customerId}`,
        sourceId: String(customerId),
        occurredAt: '2026-11-12T08:00:00Z',
        title: customerId === 1010 ? '王女士回访' : '李女士初诊沟通',
        summary: '记录正常',
        details: {},
        actorUserId: 2,
      }],
      nextCursor: null,
    }))

    const wrapper = mount(WecomGroupChatPanel, {
      props: {
        groupContext,
        chatId: 'wr-group-01',
        chatEntry: 'group_chat_tools',
      },
      global: {
        stubs: {
          WecomQuickActionsPanel: {
            props: ['customer'],
            template: '<div data-testid="quick-actions-stub" :data-customer-id="customer?.id ?? \'\'" />',
          },
          WecomQuickReplyPanel: { template: '<div data-testid="group-qr-stub" />' },
          WecomH5CardPanel: { template: '<div data-testid="group-h5-stub" />' },
          WecomMaterialPanel: { template: '<div data-testid="group-mat-stub" />' },
        },
      },
    })
    wrappers.push(wrapper)

    // Step 1: Select Member A in MEMBERS tab
    await wrapper.get('[data-testid="group-member-1010"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="quick-actions-stub"]').attributes('data-customer-id')).toBe('1010')
    expect(wrapper.get('[data-testid="group-member-profile-panel"]').text()).toContain('高意向')

    // Step 2: Switch to TOOLS and pick Member B
    await wrapper.get('[data-testid="group-tools-tab"]').trigger('click')
    await wrapper.get('[data-testid="group-tool-customer-select"]').setValue('2020')
    await flushPromises()

    // Step 3: Switch back to MEMBERS -> must show Member B's detail/packages/timeline, never Member A's!
    await wrapper.get('[data-testid="group-members-tab"]').trigger('click')
    expect(getCustomer).toHaveBeenCalledWith(2020)
    expect(wrapper.get('[data-testid="quick-actions-stub"]').attributes('data-customer-id')).toBe('2020')
    expect(wrapper.get('[data-testid="group-member-profile-panel"]').text()).toContain('低意向')
    expect(wrapper.get('[data-testid="group-member-profile-panel"]').text()).not.toContain('高意向')

    await wrapper.get('[data-testid="group-member-packages-tab"]').trigger('click')
    expect(wrapper.get('[data-testid="group-member-packages-panel"]').text()).toContain('热玛吉紧致方案')
    expect(wrapper.get('[data-testid="group-member-packages-panel"]').text()).not.toContain('水光焕肤护理')

    await wrapper.get('[data-testid="group-member-timeline-tab"]').trigger('click')
    expect(wrapper.get('[data-testid="group-member-timeline-panel"]').text()).toContain('李女士初诊沟通')
    expect(wrapper.get('[data-testid="group-member-timeline-panel"]').text()).not.toContain('王女士回访')

    // Step 4: Clear customer selection in TOOLS -> MEMBERS returns cleanly to member list
    await wrapper.get('[data-testid="group-tools-tab"]').trigger('click')
    await wrapper.get('[data-testid="group-tool-customer-select"]').setValue('')
    await wrapper.get('[data-testid="group-members-tab"]').trigger('click')
    expect(wrapper.find('[data-testid="group-member-1010"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="group-member-2020"]').exists()).toBe(true)
  })
})
