import { defineComponent, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getCustomerTimeline } from '../src/api/customerTimeline'
import { listEntitlements } from '../src/api/trading'
import { getWecomSidebarContext, getWecomSidebarGroupContext } from '../src/api/wecomSidebar'
import { routes } from '../src/router'
import {
  getWecomChatContext,
  isWecomRuntime,
  peekWecomChatContext,
  redirectToWecomOAuth,
} from '../src/services/wecomSdk'
import WecomSidebarView from '../src/views/WecomSidebarView.vue'

vi.mock('../src/services/wecomSdk', () => ({
  getWecomChatContext: vi.fn(),
  peekWecomChatContext: vi.fn(),
  isWecomRuntime: vi.fn(),
  redirectToWecomOAuth: vi.fn(),
}))
vi.mock('../src/api/wecomSidebar', () => ({
  getWecomSidebarContext: vi.fn(),
  getWecomSidebarGroupContext: vi.fn(),
}))
vi.mock('../src/api/trading', () => ({ listEntitlements: vi.fn() }))
vi.mock('../src/api/customerTimeline', () => ({ getCustomerTimeline: vi.fn() }))

let sidebarAllowed = true
let loggedIn = true
vi.mock('../src/stores/auth', () => ({
  useAuthStore: () => ({
    initialized: true,
    get isLoggedIn() {
      return loggedIn
    },
    fetchMe: vi.fn(),
    hasPermission: (code: string) => code !== 'wecom:sidebar:view' || sidebarAllowed,
  }),
}))

const customerA = {
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

const customerB = {
  ...customerA,
  id: 2020,
  customerNo: 'CU2020',
  displayName: '李女士',
}

const readyContextA = {
  contextStatus: 'READY' as const,
  corpId: 'MOCK-CORP-001',
  externalUserid: 'external-1001',
  employee: { userId: 2, displayName: '张管家' },
  customer: customerA,
  summary: { totalOrders: 3, remainingPackages: 2, remainingCount: 8, lastFollowupAt: customerA.lastFollowupAt },
}

const readyContextB = {
  ...readyContextA,
  externalUserid: 'external-2002',
  customer: customerB,
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

const StatefulDraftStub = defineComponent({
  name: 'StatefulDraftStub',
  props: {
    customerId: { type: Number, default: 0 },
    externalUserid: { type: String, default: '' },
    chatEntry: { type: String, default: '' },
  },
  emits: ['context-mismatch'],
  setup() {
    const draft = ref('')
    return { draft }
  },
  template: `
    <div data-testid="stateful-draft-stub">
      <input data-testid="stub-draft-input" v-model="draft" />
      <button type="button" data-testid="stub-emit-mismatch" @click="$emit('context-mismatch')">mismatch</button>
    </div>
  `,
})

const wrappers: VueWrapper[] = []

async function mountView(stubs: Record<string, unknown> = {}) {
  const wrapper = mount(WecomSidebarView, {
    global: {
      stubs: {
        WecomQuickActionsPanel: { template: '<div data-testid="quick-actions-stub" />' },
        ...stubs,
      },
    },
  })
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}

describe('企微窄屏客户侧边栏', () => {
  beforeEach(() => {
    sidebarAllowed = true
    loggedIn = true
    vi.clearAllMocks()
    window.sessionStorage.clear()
    window.history.replaceState({}, '', '/wecom/sidebar')
    vi.mocked(isWecomRuntime).mockReturnValue(true)
    vi.mocked(getWecomChatContext).mockResolvedValue({
      kind: 'CUSTOMER', entry: 'single_chat_tools', externalUserid: 'external-1001',
    })
    vi.mocked(peekWecomChatContext).mockResolvedValue({
      kind: 'CUSTOMER', entry: 'single_chat_tools', externalUserid: 'external-1001',
    })
    vi.mocked(getWecomSidebarContext).mockResolvedValue(readyContextA)
    vi.mocked(getWecomSidebarGroupContext).mockResolvedValue({
      contextStatus: 'READY',
      corpId: 'MOCK-CORP-001',
      chatId: 'wr-group-01',
      employee: { userId: 2, displayName: '张管家' },
      room: {
        roomId: 'wr-group-01',
        displayName: '贵宾售后群',
        status: 'ACTIVE',
        staffCount: 2,
        externalCount: 2,
        linkedCustomerCount: 1,
      },
      customers: [],
    })
    vi.mocked(listEntitlements).mockResolvedValue({ items: [entitlement], total: 1, page: 1, size: 50 })
    vi.mocked(getCustomerTimeline).mockResolvedValue({
      items: [{
        type: 'FOLLOWUP',
        eventKey: 'f-1',
        sourceId: '1',
        occurredAt: '2026-11-12T08:00:00Z',
        title: '术后第 7 天回访',
        summary: '恢复情况已记录',
        details: {},
        actorUserId: 2,
      }],
      nextCursor: null,
    })
  })

  afterEach(() => {
    vi.useRealTimers()
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

  it('群聊加载群上下文面板，未关联客户与无权限均有独立状态', async () => {
    vi.mocked(getWecomChatContext).mockResolvedValueOnce({
      kind: 'GROUP_CHAT', entry: 'group_chat_tools', chatId: 'wr-group-01',
    })
    const group = await mountView()
    expect(group.get('[data-testid="group-chat-state"]').text()).toContain('贵宾售后群')
    expect(getWecomSidebarGroupContext).toHaveBeenCalledWith('wr-group-01')
    expect(getWecomSidebarContext).not.toHaveBeenCalled()

    vi.mocked(getWecomSidebarContext).mockResolvedValueOnce({
      ...readyContextA,
      contextStatus: 'CUSTOMER_UNLINKED',
      customer: null,
      summary: { totalOrders: null, remainingPackages: null, remainingCount: null, lastFollowupAt: null },
    })
    const unlinked = await mountView()
    expect(unlinked.text()).toContain('尚未关联 CRM 客户')
    expect(unlinked.find('[data-testid="quick-actions-stub"]').exists()).toBe(true)

    sidebarAllowed = false
    const denied = await mountView()
    expect(denied.get('[data-testid="forbidden-state"]').text()).toContain('无侧边栏权限')
    expect(redirectToWecomOAuth).not.toHaveBeenCalled()
  })

  it('工具页可切换快捷话术、H5 卡片和素材库 3 个子标签', async () => {
    const wrapper = await mountView({
      WecomQuickReplyPanel: { template: '<div data-testid="quick-tool-stub">快捷话术工作区</div>' },
      WecomH5CardPanel: { template: '<div data-testid="h5-tool-stub">H5 卡片工作区</div>' },
      WecomMaterialPanel: { template: '<div data-testid="material-tool-stub">素材库工作区</div>' },
    })
    await wrapper.get('[data-testid="sidebar-tools-tab"]').trigger('click')
    expect(wrapper.find('[data-testid="quick-tool-stub"]').exists()).toBe(true)

    await wrapper.get('[data-testid="h5-card-tool-tab"]').trigger('click')
    expect(wrapper.find('[data-testid="h5-tool-stub"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="quick-tool-stub"]').exists()).toBe(false)

    await wrapper.get('[data-testid="material-tool-tab"]').trigger('click')
    expect(wrapper.find('[data-testid="material-tool-stub"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="h5-tool-stub"]').exists()).toBe(false)
  })

  it('KeepAlive 在同客户切 Tab 时保留草稿，切换客户后彻底重置', async () => {
    const wrapper = await mountView({
      WecomAiReplyPanel: StatefulDraftStub,
    })

    await wrapper.get('[data-testid="sidebar-ai-tab"]').trigger('click')
    await wrapper.get('[data-testid="stub-draft-input"]').setValue('给王女士的草稿')

    // Switch away to Customer tab and back to AI tab -> draft survives!
    await wrapper.get('[data-testid="sidebar-customer-tab"]').trigger('click')
    expect(wrapper.find('[data-testid="stub-draft-input"]').exists()).toBe(false)
    await wrapper.get('[data-testid="sidebar-ai-tab"]').trigger('click')
    expect((wrapper.get('[data-testid="stub-draft-input"]').element as HTMLInputElement).value).toBe('给王女士的草稿')

    // Switch to another customer (customerB) -> KeepAlive resets cleanly!
    vi.mocked(getWecomChatContext).mockResolvedValueOnce({
      kind: 'CUSTOMER', entry: 'single_chat_tools', externalUserid: 'external-2002',
    })
    vi.mocked(getWecomSidebarContext).mockResolvedValueOnce(readyContextB)
    await wrapper.get('[aria-label="刷新当前客户"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('李女士')
    expect((wrapper.get('[data-testid="stub-draft-input"]').element as HTMLInputElement).value).toBe('')
  })

  it('OAuth 失败参数展示友好排查页且不循环跳转，30 秒内防重入守卫拦截自动跳转', async () => {
    window.history.replaceState(
      {},
      '',
      '/wecom/sidebar?oauth_error=UNAUTHORIZED&oauth_message=%E8%B4%A6%E5%8F%B7%E6%9C%AA%E7%BB%91%E5%AE%9A',
    )
    loggedIn = false
    const errWrapper = await mountView()
    expect(errWrapper.get('[data-testid="oauth-error-state"]').text()).toContain('企业微信身份验证未通过')
    expect(errWrapper.get('[data-testid="oauth-error-state"]').text()).toContain('账号未绑定')
    expect(redirectToWecomOAuth).not.toHaveBeenCalled()

    // Manual retry clears query and initiates OAuth
    await errWrapper.get('[data-testid="oauth-manual-retry"]').trigger('click')
    expect(redirectToWecomOAuth).toHaveBeenCalledTimes(1)
    expect(window.location.search).toBe('')

    // Second unauthenticated mount within 30s triggers redirect-loop guard
    vi.mocked(redirectToWecomOAuth).mockClear()
    const loopWrapper = await mountView()
    expect(loopWrapper.get('[data-testid="oauth-error-state"]').text()).toContain('已暂停自动授权跳转')
    expect(redirectToWecomOAuth).not.toHaveBeenCalled()
  })

  it('窗口聚焦/可见性恢复时自动检测企微会话切换并刷新，context-mismatch 提示并重载', async () => {
    vi.useFakeTimers()
    const wrapper = await mountView({
      WecomAiReplyPanel: StatefulDraftStub,
    })
    expect(wrapper.text()).toContain('王女士')

    // Emit context-mismatch from child panel -> warns & reloads
    await wrapper.get('[data-testid="sidebar-ai-tab"]').trigger('click')
    vi.mocked(getWecomChatContext).mockResolvedValueOnce({
      kind: 'CUSTOMER', entry: 'single_chat_tools', externalUserid: 'external-2002',
    })
    vi.mocked(getWecomSidebarContext).mockResolvedValueOnce(readyContextB)
    await wrapper.get('[data-testid="stub-emit-mismatch"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="mismatch-notice"]').text()).toContain('已阻止误发')
    expect(wrapper.text()).toContain('李女士')

    // Window focus detects switch back to customerA after debounce
    vi.mocked(peekWecomChatContext).mockResolvedValueOnce({
      kind: 'CUSTOMER', entry: 'single_chat_tools', externalUserid: 'external-1001',
    })
    vi.mocked(getWecomChatContext).mockResolvedValueOnce({
      kind: 'CUSTOMER', entry: 'single_chat_tools', externalUserid: 'external-1001',
    })
    vi.mocked(getWecomSidebarContext).mockResolvedValueOnce(readyContextA)

    window.dispatchEvent(new Event('focus'))
    vi.advanceTimersByTime(350)
    await flushPromises()

    expect(peekWecomChatContext).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('王女士')
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
