import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessage } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuthUser } from '../src/api/auth'
import type { CustomerDetail, CustomerListItem } from '../src/api/customer'
import { getCustomer, listCustomers } from '../src/api/customer'
import type { WecomContact, WecomMessageView, WecomMonitorView } from '../src/api/wecom'
import {
  getWecomMessageContent,
  getWecomMonitor,
  linkWecomContact,
  listWecomContacts,
  listWecomContactSyncRuns,
  listWecomMessages,
  syncWecomArchive,
  syncWecomContacts,
} from '../src/api/wecom'
import { useAuthStore } from '../src/stores/auth'
import { ApiError } from '../src/types/api'
import WecomArchiveView from '../src/views/WecomArchiveView.vue'
import workspaceSource from '../src/views/WecomArchiveView.vue?raw'
import WecomArchiveAdminView from '../src/views/WecomArchiveAdminView.vue'

vi.mock('../src/api/wecom', () => ({
  listWecomMessages: vi.fn(),
  getWecomMessageContent: vi.fn(),
  listWecomContacts: vi.fn(),
  listWecomContactSyncRuns: vi.fn(),
  getWecomMonitor: vi.fn(),
  syncWecomArchive: vi.fn(),
  syncWecomContacts: vi.fn(),
  linkWecomContact: vi.fn(),
}))
vi.mock('../src/api/customer', () => ({ listCustomers: vi.fn(), getCustomer: vi.fn() }))
vi.mock('vue-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('vue-router')>()),
  useRoute: () => ({ query: {} }),
}))

const message: WecomMessageView = {
  id: 901,
  corpId: 'MOCK-CORP-001',
  msgId: 'WECOM-MSG-001',
  archiveSeq: 101,
  customerId: 42,
  externalUserid: 'WECOM-EXT-1010',
  staffUserId: 2,
  conversationId: 'MOCK-CONV-1010',
  messageType: 'TEXT',
  senderType: 'CUSTOMER',
  sentAt: '2026-10-25T01:00:00Z',
  contentObjectKey: 'wecom/mock-private-object',
  consentSnapshot: 'GRANTED',
  currentConsentStatus: 'GRANTED',
  status: 'STORED',
}

const contact: WecomContact = {
  id: 2,
  corpId: 'MOCK-CORP-001',
  externalUserid: 'WECOM-EXT-UNMATCHED',
  staffUserId: 2,
  name: '虚构企微客户乙',
  avatar: null,
  contactType: 1,
  gender: 0,
  unionid: null,
  remark: '待人工匹配',
  description: null,
  addWay: 1,
  channelState: 'mock-channel',
  followCreatedAt: '2026-10-25T01:00:00Z',
  tagSnapshotJson: null,
  followStatus: 'ACTIVE',
  customerId: null,
  consentStatus: 'GRANTED',
  consentEvidenceRef: 'mock://consent/unmatched',
  linkType: null,
  linkReason: null,
  createdBy: null,
  lastSyncedAt: '2026-10-25T01:00:00Z',
  version: 3,
}

const monitor: WecomMonitorView = {
  corpId: 'MOCK-CORP-001',
  archiveScope: 'MOCK_ARCHIVE',
  lastSeq: 100,
  lastSuccessAt: '2026-10-25T01:00:00Z',
  lastAttemptAt: '2026-10-25T01:02:00Z',
  status: 'ERROR',
  dataGap: true,
  gapReason: '成员不在归档覆盖范围',
  messageCount: 0,
  unmatchedCount: 1,
  activeGaps: 1,
  historicalGaps: 2,
}

const customer: CustomerListItem = {
  id: 42,
  customerNo: 'C0000000042',
  displayName: '测试客户',
  nickname: null,
  mobileMasked: '138****0000',
  cityCode: null,
  customerStatus: 'ACTIVE',
  tierCode: null,
  intentLevel: null,
  ownerStaffId: 9,
  ownerName: '测试管家',
  teamId: 1,
  pointsCached: 0,
  lastFollowupAt: null,
  createdAt: '2026-10-24T01:00:00Z',
  version: 0,
}

const customerDetail: CustomerDetail = {
  ...customer,
  sourceCode: null,
  channelCode: null,
  spendingLevel: null,
  birthday: null,
  attentionProjects: null,
  blockerNote: null,
  remark: null,
  remarkName: null,
  firstAddedAt: null,
  firstOrderAt: null,
  tagCodes: [],
  identities: [],
  deleted: false,
}

function makeUser(permissions: string[], userType = 'EMPLOYEE'): AuthUser {
  return {
    userId: 1,
    username: 'wecom-test-admin',
    displayName: '企微测试管理员',
    userType,
    roles: userType === 'INSTITUTION' ? ['INSTITUTION_OPERATOR'] : ['ADMIN'],
    permissions,
  }
}

const wrappers: VueWrapper[] = []
afterEach(() => {
  while (wrappers.length) wrappers.pop()?.unmount()
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})

function mountView(permissions: string[], userType = 'EMPLOYEE') {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = makeUser(permissions, userType)
  auth.initialized = true
  const wrapper = mount(WecomArchiveAdminView, {
    global: { plugins: [ElementPlus, pinia] },
    attachTo: document.body,
  })
  wrappers.push(wrapper)
  return wrapper
}

function mountWorkspace(permissions: string[], userType = 'EMPLOYEE') {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = makeUser(permissions, userType)
  auth.initialized = true
  const wrapper = mount(WecomArchiveView, {
    global: {
      plugins: [ElementPlus, pinia],
      stubs: { RouterLink: { props: ['to'], template: '<a :data-to="to"><slot /></a>' } },
    },
    attachTo: document.body,
  })
  wrappers.push(wrapper)
  return wrapper
}

function component(wrapper: VueWrapper, testId: string): VueWrapper {
  return wrapper.getComponent(`[data-testid="${testId}"]`) as VueWrapper
}

async function openTab(wrapper: VueWrapper, label: string) {
  const tab = wrapper.findAll('.el-tabs__item').find((item) => item.text() === label)
  expect(tab).toBeDefined()
  await tab?.trigger('click')
  await flushPromises()
}

async function flushDrawer() {
  await flushPromises()
  await new Promise((resolve) => setTimeout(resolve, 30))
}

describe('WecomArchiveAdminView 合规归档与 W02', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(listWecomMessages).mockResolvedValue({ items: [], total: 0, page: 1, size: 20 })
    vi.mocked(listWecomContacts).mockResolvedValue([contact])
    vi.mocked(listWecomContactSyncRuns).mockResolvedValue([])
    vi.mocked(syncWecomContacts).mockResolvedValue({ id: 1, corpId: contact.corpId, runType: 'FULL', status: 'SUCCESS', cursorValue: null, receivedCount: 1, upsertedCount: 1, deactivatedCount: 0, errorMasked: null, startedAt: '2026-10-25T01:00:00Z', finishedAt: '2026-10-25T01:00:00Z' })
    vi.mocked(getWecomMonitor).mockResolvedValue(monitor)
    vi.mocked(getWecomMessageContent).mockResolvedValue({ messageRef: 901, messageType: 'TEXT', sentAt: message.sentAt, content: 'Mock 授权归档文本' })
    vi.mocked(syncWecomArchive).mockResolvedValue({ adapter: 'MOCK_DEV_ONLY', received: 2, stored: 1, duplicates: 0, skipped: 1, lastSeq: 102, hasMore: false, dataGap: true })
    vi.mocked(linkWecomContact).mockResolvedValue({ ...contact, customerId: 42, linkType: 'MANUAL', linkReason: '人工核对外部身份与CRM档案', createdBy: 1, version: 4 })
    vi.mocked(listCustomers).mockResolvedValue({ items: [customer], total: 1, page: 1, size: 20 })
    vi.mocked(getCustomer).mockResolvedValue(customerDetail)
  })

  it('W02 空元数据与 dataGap 显示采集覆盖缺口，不把 0 条写成无沟通', async () => {
    const wrapper = mountView(['wecom:read'])
    await flushPromises()

    expect(wrapper.get('[data-testid="wecom-mock-banner"]').text()).toContain('模拟')
    expect(wrapper.text()).toContain('Q10 未确认')
    expect(wrapper.text()).toContain('官方 SDK 禁用')
    expect(wrapper.text()).toContain('不接个人微信')
    expect(wrapper.get('[data-testid="wecom-data-gap"]').text()).toContain('采集覆盖缺口')
    expect(wrapper.text()).toContain('无法判断沟通情况')
    expect(wrapper.text()).toContain('成员不在归档覆盖范围')
    expect(wrapper.text()).toContain('0 条（不等于沟通总量）')
    expect(wrapper.text()).not.toContain('无沟通')
    expect(wrapper.find('[data-testid="wecom-sync"]').exists()).toBe(false)

    await openTab(wrapper, '联系人与人工匹配')
    expect(wrapper.find('[data-testid="wecom-contact-link"]').exists()).toBe(false)
  })

  it.each([
    { permissions: [] as string[], userType: 'EMPLOYEE', message: '缺少 wecom:read 权限' },
    { permissions: ['wecom:read', 'wecom:monitor'], userType: 'INSTITUTION', message: '机构用户无企业微信归档访问入口' },
  ])('无权限或机构主体不调用归档接口且不展示操作入口 ($message)', async ({ permissions, userType, message: expected }) => {
    const wrapper = mountView(permissions, userType)
    await flushPromises()

    expect(wrapper.text()).toContain(expected)
    expect(wrapper.find('[data-testid="wecom-sync"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="wecom-content-read"]').exists()).toBe(false)
    expect(listWecomMessages).not.toHaveBeenCalled()
    expect(listWecomContacts).not.toHaveBeenCalled()
    expect(getWecomMonitor).not.toHaveBeenCalled()
    expect(getWecomMessageContent).not.toHaveBeenCalled()
    expect(syncWecomArchive).not.toHaveBeenCalled()
    expect(linkWecomContact).not.toHaveBeenCalled()
    expect(getCustomer).not.toHaveBeenCalled()
    expect(listCustomers).not.toHaveBeenCalled()
  })

  it('监控读取失败也明确覆盖缺口与无法判断沟通情况', async () => {
    vi.mocked(getWecomMonitor).mockRejectedValue(new ApiError({ status: 0, code: 'NETWORK_ERROR', message: '监控不可用' }))
    const wrapper = mountView(['wecom:read'])
    await flushPromises()

    expect(wrapper.get('[data-testid="wecom-monitor-error"]').text()).toContain('监控不可用')
    expect(wrapper.text()).toContain('采集覆盖缺口')
    expect(wrapper.text()).toContain('无法判断沟通情况')
  })

  it('内容再次读取失败时清空旧全文并展示后端错误', async () => {
    vi.mocked(listWecomMessages).mockResolvedValue({ items: [message], total: 1, page: 1, size: 20 })
    vi.mocked(getWecomMessageContent)
      .mockResolvedValueOnce({ messageRef: 901, messageType: 'TEXT', sentAt: message.sentAt, content: '旧授权全文' })
      .mockRejectedValueOnce(new ApiError({ status: 403, code: 'FORBIDDEN', message: '本次内容授权失败' }))
    const wrapper = mountView(['wecom:read'])
    await flushPromises()
    expect(wrapper.text()).not.toContain('旧授权全文')
    expect(wrapper.text()).not.toContain('wecom/mock-private-object')

    await wrapper.get('[data-testid="wecom-content-read"]').trigger('click')
    await flushDrawer()
    expect(getWecomMessageContent).toHaveBeenCalledWith(901)
    expect(document.body.textContent).toContain('旧授权全文')

    const close = Array.from(document.body.querySelectorAll<HTMLButtonElement>('button')).find((button) => button.textContent?.includes('关闭并清除'))
    expect(close).toBeDefined()
    close?.click()
    await flushDrawer()
    expect(document.body.textContent).not.toContain('旧授权全文')

    await wrapper.get('[data-testid="wecom-content-read"]').trigger('click')
    await flushDrawer()
    expect(getWecomMessageContent).toHaveBeenCalledTimes(2)
    expect(document.body.textContent).toContain('本次内容授权失败')
    expect(document.body.textContent).not.toContain('旧授权全文')
    expect(document.body.querySelector('[data-testid="wecom-content-text"]')).toBeNull()
  })

  it('同意已撤回的消息不展示内容读取入口', async () => {
    vi.mocked(listWecomMessages).mockResolvedValue({ items: [{ ...message, id: 902, msgId: 'WECOM-MSG-REVOKED', currentConsentStatus: 'REVOKED' }], total: 1, page: 1, size: 20 })
    const wrapper = mountView(['wecom:read'])
    await flushPromises()

    expect(wrapper.text()).toContain('同意无效不可读')
    expect(wrapper.find('[data-testid="wecom-content-read"]').exists()).toBe(false)
    expect(getWecomMessageContent).not.toHaveBeenCalled()
  })

  it('未关联队列人工选择可见客户并提交理由与版本', async () => {
    const wrapper = mountView(['wecom:read', 'wecom:monitor'])
    await flushPromises()
    await openTab(wrapper, '联系人与人工匹配')
    await wrapper.get('[data-testid="wecom-contact-link"]').trigger('click')
    await flushPromises()
    expect(listCustomers).toHaveBeenCalledWith({ page: 1, size: 20, keyword: undefined })

    component(wrapper, 'wecom-link-customer').vm.$emit('change', 42)
    await wrapper.get('[data-testid="wecom-link-save"]').trigger('click')
    expect(linkWecomContact).not.toHaveBeenCalled()

    await wrapper.get('[data-testid="wecom-link-reason"]').setValue('人工核对外部身份与CRM档案')
    await wrapper.get('[data-testid="wecom-link-save"]').trigger('click')
    await flushPromises()
    expect(linkWecomContact).toHaveBeenCalledWith(2, { customerId: 42, version: 3, reason: '人工核对外部身份与CRM档案' }, expect.any(String))
    expect(listWecomContacts).toHaveBeenCalledTimes(2)
    expect(listWecomMessages).toHaveBeenCalledTimes(2)
  })

  it('Mock 同步使用真实游标，失败重试沿用幂等键并刷新缺口', async () => {
    const warning = vi.spyOn(ElMessage, 'warning')
    const error = vi.spyOn(ElMessage, 'error')
    vi.mocked(syncWecomArchive)
      .mockRejectedValueOnce(new ApiError({ status: 409, code: 'CONFLICT', message: 'Mock 归档失败' }))
      .mockResolvedValueOnce({ adapter: 'MOCK_DEV_ONLY', received: 2, stored: 1, duplicates: 0, skipped: 1, lastSeq: 102, hasMore: false, dataGap: true })
    const wrapper = mountView(['wecom:read', 'wecom:monitor'])
    await flushPromises()

    await wrapper.get('[data-testid="wecom-sync"]').trigger('click')
    await flushPromises()
    expect(syncWecomArchive).toHaveBeenNthCalledWith(1, { corpId: 'MOCK-CORP-001', cursor: 100, limit: 50 }, expect.any(String))
    expect(error).toHaveBeenCalledWith(expect.stringContaining('重试将沿用同一幂等键'))

    await wrapper.get('[data-testid="wecom-sync"]').trigger('click')
    await flushPromises()
    expect(syncWecomArchive).toHaveBeenCalledTimes(2)
    const firstKey = vi.mocked(syncWecomArchive).mock.calls[0]?.[1]
    const secondKey = vi.mocked(syncWecomArchive).mock.calls[1]?.[1]
    expect(firstKey).toBeTruthy()
    expect(secondKey).toBe(firstKey)
    expect(warning).toHaveBeenCalledWith(expect.stringContaining('采集覆盖缺口'))
    expect(listWecomMessages).toHaveBeenCalledTimes(2)
    expect(listWecomContacts).toHaveBeenCalledTimes(2)
    expect(getWecomMonitor).toHaveBeenCalledTimes(3)
  })
})

describe('WecomArchiveView 三栏会话工作区', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(listWecomMessages).mockResolvedValue({ items: [], total: 0, page: 1, size: 100 })
    vi.mocked(listWecomContacts).mockResolvedValue([contact])
    vi.mocked(getWecomMessageContent).mockResolvedValue({ messageRef: 901, messageType: 'TEXT', sentAt: message.sentAt, content: 'Mock 授权归档文本' })
    vi.mocked(getCustomer).mockResolvedValue(customerDetail)
    vi.mocked(listCustomers).mockResolvedValue({ items: [customer], total: 1, page: 1, size: 20 })
  })

  it('三栏页面读取真实会话与客户，展示真实 AI 空状态并隐藏无权限操作', async () => {
    vi.mocked(listWecomMessages).mockResolvedValue({ items: [message], total: 1, page: 1, size: 100 })
    const wrapper = mountWorkspace(['wecom:read'])
    await flushPromises()

    expect(listWecomMessages).toHaveBeenCalledWith({ page: 1, size: 100 })
    expect(listWecomContacts).toHaveBeenCalledTimes(1)
    expect(getCustomer).toHaveBeenCalledWith(42)
    expect(getWecomMessageContent).toHaveBeenCalledWith(901)
    expect(getWecomMonitor).not.toHaveBeenCalled()
    expect(syncWecomArchive).not.toHaveBeenCalled()
    expect(linkWecomContact).not.toHaveBeenCalled()
    expect(listCustomers).not.toHaveBeenCalled()

    expect(wrapper.find('.chat-layout').exists()).toBe(true)
    expect(wrapper.find('.chat-contacts').exists()).toBe(true)
    expect(wrapper.find('.chat-panel').exists()).toBe(true)
    expect(wrapper.find('.ai-panel').exists()).toBe(true)
    expect(wrapper.findAll('.chat-contact')).toHaveLength(1)
    expect(wrapper.text()).toContain('测试客户')
    expect(wrapper.text()).toContain('Mock 授权归档文本')
    expect(wrapper.text()).not.toContain('wecom/mock-private-object')
    expect(wrapper.get('a[data-to="/integrations/wecom/archive"]').text()).toContain('接入状态 / 归档管理')
    expect(wrapper.text()).toContain('尚未生成本次会话的 AI 分析')
    expect(wrapper.text()).toContain('人工确认前不改变业务事实')
    expect(wrapper.find('[data-testid="ai-analyze"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="ai-confirm-open"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="wecom-monitor"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="wecom-sync"]').exists()).toBe(false)
  })

  it.each([
    { permissions: [] as string[], userType: 'EMPLOYEE', message: '缺少 wecom:read 权限' },
    { permissions: ['wecom:read', 'wecom:monitor'], userType: 'INSTITUTION', message: '机构用户无企业微信归档访问入口' },
  ])('主页面无权限或机构主体不调用任何 API ($message)', async ({ permissions, userType, message: expected }) => {
    const wrapper = mountWorkspace(permissions, userType)
    await flushPromises()

    expect(wrapper.text()).toContain(expected)
    expect(wrapper.find('.chat-layout').exists()).toBe(false)
    expect(listWecomMessages).not.toHaveBeenCalled()
    expect(listWecomContacts).not.toHaveBeenCalled()
    expect(getWecomMessageContent).not.toHaveBeenCalled()
    expect(getWecomMonitor).not.toHaveBeenCalled()
    expect(syncWecomArchive).not.toHaveBeenCalled()
    expect(linkWecomContact).not.toHaveBeenCalled()
    expect(getCustomer).not.toHaveBeenCalled()
    expect(listCustomers).not.toHaveBeenCalled()
  })

  it('会话元数据读取失败展示真实错误，不伪造会话内容', async () => {
    vi.mocked(listWecomMessages).mockRejectedValue(new ApiError({ status: 0, code: 'NETWORK_ERROR', message: '会话元数据不可用' }))
    const wrapper = mountWorkspace(['wecom:read'])
    await flushPromises()

    expect(wrapper.text()).toContain('会话元数据不可用')
    expect(wrapper.findAll('.chat-contact')).toHaveLength(0)
    expect(wrapper.text()).not.toContain('Mock 授权归档文本')
    expect(getWecomMessageContent).not.toHaveBeenCalled()
    expect(getCustomer).not.toHaveBeenCalled()
  })

  it('切换会话读取失败时清空上一会话全文并提示授权缺口', async () => {
    const firstMessage: WecomMessageView = { ...message, archiveSeq: 102, conversationId: 'MOCK-CONV-A' }
    const secondMessage: WecomMessageView = {
      ...message,
      id: 902,
      msgId: 'WECOM-MSG-002',
      archiveSeq: 101,
      customerId: null,
      externalUserid: 'WECOM-EXT-B',
      conversationId: 'MOCK-CONV-B',
      contentObjectKey: 'wecom/mock-private-object-b',
    }
    vi.mocked(listWecomMessages).mockResolvedValue({ items: [firstMessage, secondMessage], total: 2, page: 1, size: 100 })
    vi.mocked(getWecomMessageContent).mockImplementation(async (id) => {
      if (id === 901) return { messageRef: 901, messageType: 'TEXT', sentAt: firstMessage.sentAt, content: '第一会话授权全文' }
      throw new ApiError({ status: 403, code: 'FORBIDDEN', message: '第二会话未获授权' })
    })
    const wrapper = mountWorkspace(['wecom:read'])
    await flushPromises()

    expect(wrapper.text()).toContain('第一会话授权全文')
    const secondConversation = wrapper.findAll('.chat-contact').find((item) => item.text().includes('待匹配 · WECOM-EXT-B'))
    expect(secondConversation).toBeDefined()
    await secondConversation?.trigger('click')
    await flushPromises()

    expect(getWecomMessageContent).toHaveBeenNthCalledWith(1, 901)
    expect(getWecomMessageContent).toHaveBeenNthCalledWith(2, 902)
    expect(wrapper.text()).toContain('部分内容未获授权或暂不可读取，未展示旧全文')
    expect(wrapper.text()).not.toContain('第一会话授权全文')
    expect(wrapper.text()).not.toContain('wecom/mock-private-object-b')
  })

  it('AI 操作按钮清除相邻左边距，保持全宽左右对齐', () => {
    expect(workspaceSource).toMatch(/\.ai-panel \.el-button\{width:100%;margin-top:10px\}/)
    expect(workspaceSource).toMatch(/\.ai-panel \.el-button \+ \.el-button\{margin-left:0\}/)
  })
})
