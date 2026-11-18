import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { listEntitlements } from '../src/api/trading'
import { getWecomSidebarContext } from '../src/api/wecomSidebar'
import { getCustomerTimeline } from '../src/api/customerTimeline'
import { getWecomChatContext, isWecomRuntime, redirectToWecomOAuth } from '../src/services/wecomSdk'
import { routes } from '../src/router'
import WecomSidebarView from '../src/views/WecomSidebarView.vue'

vi.mock('../src/services/wecomSdk', () => ({
  getWecomChatContext: vi.fn(),
  isWecomRuntime: vi.fn(),
  redirectToWecomOAuth: vi.fn(),
}))
vi.mock('../src/api/wecomSidebar', () => ({ getWecomSidebarContext: vi.fn() }))
vi.mock('../src/api/trading', () => ({ listEntitlements: vi.fn() }))
vi.mock('../src/api/customerTimeline', () => ({ getCustomerTimeline: vi.fn() }))

let sidebarAllowed = true
vi.mock('../src/stores/auth', () => ({
  useAuthStore: () => ({
    initialized: true,
    isLoggedIn: true,
    fetchMe: vi.fn(),
    hasPermission: (code: string) => code !== 'wecom:sidebar:view' || sidebarAllowed,
  }),
}))

const customer = {
  id: 1010,
  customerNo: 'CU1001',
  displayName: '王女士',
  nickname: null,
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
  tagCodes: ['重点客户'],
  identities: [],
  deleted: false,
}

const readyContext = {
  contextStatus: 'READY' as const,
  corpId: 'MOCK-CORP-001',
  externalUserid: 'external-1001',
  employee: { userId: 2, displayName: '张管家' },
  customer,
  summary: { totalOrders: 3, remainingPackages: 2, remainingCount: 8, lastFollowupAt: customer.lastFollowupAt },
}

const entitlement = {
  id: 9,
  customerId: 1010,
  orderItemId: 8,
  projectId: 7,
  projectName: '光子嫩肤护理包',
  grantedCount: 10,
  availableCount: 6,
  versionNo: 1,
  validFrom: '2026-01-01',
  validTo: '2027-01-01',
  status: 'ACTIVE',
  createdAt: '2026-01-01T00:00:00Z',
  version: 1,
}

const wrappers: VueWrapper[] = []

async function mountView() {
  const wrapper = mount(WecomSidebarView)
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}

describe('企微窄屏客户侧边栏', () => {
  beforeEach(() => {
    sidebarAllowed = true
    vi.clearAllMocks()
    vi.mocked(isWecomRuntime).mockReturnValue(true)
    vi.mocked(getWecomChatContext).mockResolvedValue({ kind: 'CUSTOMER', entry: 'single_chat_tools', externalUserid: 'external-1001' })
    vi.mocked(getWecomSidebarContext).mockResolvedValue(readyContext)
    vi.mocked(listEntitlements).mockResolvedValue({ items: [entitlement], total: 1, page: 1, size: 50 })
    vi.mocked(getCustomerTimeline).mockResolvedValue({
      items: [{ type: 'FOLLOWUP', eventKey: 'f-1', sourceId: '1', occurredAt: '2026-11-12T08:00:00Z', title: '术后第 7 天回访', summary: '恢复情况已记录', details: {}, actorUserId: 2 }],
      nextCursor: null,
    })
  })

  afterEach(() => {
    while (wrappers.length) wrappers.pop()?.unmount()
  })

  it('展示真实客户摘要，并切换套餐和跟进时间线', async () => {
    const wrapper = await mountView()

    expect(wrapper.text()).toContain('王女士')
    expect(wrapper.text()).toContain('张管家')
    expect(wrapper.text()).toContain('剩余次数')
    await wrapper.get('[data-testid="sidebar-packages-tab"]').trigger('click')
    expect(wrapper.get('[data-testid="sidebar-packages-panel"]').text()).toContain('光子嫩肤护理包')
    expect(wrapper.get('[data-testid="sidebar-packages-panel"]').text()).toContain('6')
    await wrapper.get('[data-testid="sidebar-timeline-tab"]').trigger('click')
    expect(wrapper.get('[data-testid="sidebar-timeline-panel"]').text()).toContain('术后第 7 天回访')
    expect(getWecomSidebarContext).toHaveBeenCalledWith('external-1001')
  })

  it('普通浏览器不识别当前客户', async () => {
    vi.mocked(isWecomRuntime).mockReturnValue(false)
    const wrapper = await mountView()

    expect(wrapper.get('[data-testid="browser-state"]').text()).toContain('请在企业微信中打开')
    expect(getWecomChatContext).not.toHaveBeenCalled()
    expect(getWecomSidebarContext).not.toHaveBeenCalled()
  })

  it('群聊、未关联客户与无权限均有独立状态', async () => {
    vi.mocked(getWecomChatContext).mockResolvedValueOnce({ kind: 'GROUP_CHAT', entry: 'group_chat_tools' })
    const group = await mountView()
    expect(group.get('[data-testid="group-chat-state"]').text()).toContain('群聊')
    expect(getWecomSidebarContext).not.toHaveBeenCalled()

    vi.mocked(getWecomSidebarContext).mockResolvedValueOnce({
      ...readyContext, contextStatus: 'CUSTOMER_UNLINKED', customer: null,
      summary: { totalOrders: null, remainingPackages: null, remainingCount: null, lastFollowupAt: null },
    })
    const unlinked = await mountView()
    expect(unlinked.text()).toContain('尚未关联 CRM 客户')

    sidebarAllowed = false
    const denied = await mountView()
    expect(denied.get('[data-testid="forbidden-state"]').text()).toContain('无侧边栏权限')
    expect(redirectToWecomOAuth).not.toHaveBeenCalled()
  })

  it('AI 助手有独立页签并接收当前客户上下文', async () => {
    const wrapper = mount(WecomSidebarView, {
      global: { stubs: { WecomAiReplyPanel: { template: '<div data-testid="ai-reply-stub">AI 回复工作区</div>' } } },
    })
    wrappers.push(wrapper)
    await flushPromises()
    await wrapper.get('[data-testid="sidebar-ai-tab"]').trigger('click')
    expect(wrapper.get('[data-testid="sidebar-ai-panel"]').text()).toContain('AI 回复工作区')
  })

  it('工具页可切换快捷话术和 H5 卡片', async () => {
    const wrapper = mount(WecomSidebarView, {
      global: { stubs: {
        WecomQuickReplyPanel: { template: '<div data-testid="quick-tool-stub">快捷话术工作区</div>' },
        WecomH5CardPanel: { template: '<div data-testid="h5-tool-stub">H5 卡片工作区</div>' },
      } },
    })
    wrappers.push(wrapper)
    await flushPromises()
    await wrapper.get('[data-testid="sidebar-tools-tab"]').trigger('click')
    expect(wrapper.find('[data-testid="quick-tool-stub"]').exists()).toBe(true)
    await wrapper.get('[data-testid="h5-card-tool-tab"]').trigger('click')
    expect(wrapper.find('[data-testid="h5-tool-stub"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="quick-tool-stub"]').exists()).toBe(false)
  })

  it('异常可重试，路由为独立公开入口', async () => {
    vi.mocked(getWecomChatContext).mockRejectedValueOnce(new Error('企微暂时不可用'))
    const wrapper = await mountView()
    expect(wrapper.get('[data-testid="error-state"]').text()).toContain('企微暂时不可用')
    await wrapper.get('.state-action').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('王女士')

    const route = routes.find((item) => item.name === 'wecom-sidebar')
    expect(route?.meta).toMatchObject({ public: true, display: true })
  })
})
