import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuthUser } from '../src/api/auth'
import { getAiQualitySummary, listAiJobs } from '../src/api/ai'
import { getWecomMonitor, listWecomContactSyncRuns, listWecomContacts, listWecomMessages } from '../src/api/wecom'
import type { WecomMessageView } from '../src/api/wecom'
import { useAuthStore } from '../src/stores/auth'
import WecomMonitorView from '../src/views/WecomMonitorView.vue'

vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
}))

vi.mock('../src/api/wecom', () => ({
  listWecomMessages: vi.fn(),
  getWecomMonitor: vi.fn(),
  listWecomContactSyncRuns: vi.fn(),
  listWecomContacts: vi.fn(),
}))

vi.mock('../src/api/ai', () => ({
  getAiQualitySummary: vi.fn(),
  listAiJobs: vi.fn(),
}))

vi.mock('../src/api/customer', () => ({
  getCustomer: vi.fn().mockRejectedValue(new Error('not needed')),
}))

vi.mock('../src/api/catalog', () => ({
  listInstitutions: vi.fn().mockResolvedValue({ items: [], total: 0, page: 1, size: 100 }),
  listProjects: vi.fn().mockResolvedValue({ items: [], total: 0, page: 1, size: 100 }),
}))

const wrappers: VueWrapper[] = []

function mountView(permissions = ['wecom:read'], userType = 'EMPLOYEE') {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = {
    userId: 1,
    username: 'wecom-monitor',
    displayName: '监管测试',
    userType,
    roles: ['ADMIN'],
    permissions,
  } satisfies AuthUser
  auth.initialized = true
  const wrapper = mount(WecomMonitorView, {
    global: {
      plugins: [ElementPlus, pinia],
      stubs: { RouterLink: { template: '<a><slot /></a>' } },
    },
    attachTo: document.body,
  })
  wrappers.push(wrapper)
  return wrapper
}

function makeMessage(partial: Partial<WecomMessageView>): WecomMessageView {
  return {
    id: 1,
    corpId: 'WW-REAL-CORP-99',
    msgId: 'MSG-1',
    archiveSeq: 100,
    conversationId: 'CONV-1',
    conversationType: 'DIRECT',
    externalUserid: 'EXT-1',
    customerId: 11,
    staffUserId: 22,
    senderType: 'CUSTOMER',
    senderWecomUserid: 'EXT-1',
    senderStaffUserId: null,
    roomRosterVersion: null,
    messageType: 'TEXT',
    sentAt: new Date(Date.now() - 48 * 3600 * 1000).toISOString(),
    contentObjectKey: 'wecom/mock/msg-1',
    consentSnapshot: 'GRANTED',
    currentConsentStatus: 'GRANTED',
    status: 'ARCHIVED',
    recalledAt: null,
    recallMessageId: null,
    ...partial,
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(listWecomContactSyncRuns).mockResolvedValue([])
  vi.mocked(listWecomContacts).mockResolvedValue([])
  vi.mocked(getWecomMonitor).mockResolvedValue({
    corpId: 'WW-REAL-CORP-99',
    archiveScope: 'CUSTOMER_AND_ROOM',
    lastSeq: 100,
    lastSuccessAt: new Date().toISOString(),
    lastAttemptAt: new Date().toISOString(),
    status: 'SUCCESS',
    dataGap: false,
    gapReason: null,
    messageCount: 2,
    unmatchedCount: 0,
    activeGaps: 0,
    historicalGaps: 0,
  })
  vi.mocked(getAiQualitySummary).mockResolvedValue({
    pendingReview: 0,
    riskSuggested: 0,
    failed: 0,
    confirmed: 0,
    riskNotice: '',
  })
  vi.mocked(listAiJobs).mockResolvedValue({ items: [], total: 0, page: 1, size: 20 })
})

afterEach(() => {
  while (wrappers.length) wrappers.pop()?.unmount()
  document.body.innerHTML = ''
})

describe('WecomMonitorView 监管逻辑与安全性', () => {
  it('动态解析真实 corpId（绝不使用 MOCK-CORP-001），并仅将客户最后发言 >24h 计为逾期', async () => {
    const customerOverdue = makeMessage({
      id: 1,
      conversationId: 'CONV-CUST',
      senderType: 'CUSTOMER',
      customerId: 101,
      sentAt: new Date(Date.now() - 30 * 3600 * 1000).toISOString(),
    })
    const employeeReplied = makeMessage({
      id: 2,
      conversationId: 'CONV-EMP',
      senderType: 'EMPLOYEE',
      customerId: 102,
      externalUserid: null,
      sentAt: new Date(Date.now() - 30 * 3600 * 1000).toISOString(),
    })
    vi.mocked(listWecomMessages).mockResolvedValue({
      items: [customerOverdue, employeeReplied],
      total: 2,
      page: 1,
      size: 100,
    })

    const wrapper = mountView(['wecom:read'])
    await flushPromises()

    expect(getWecomMonitor).toHaveBeenCalledWith('WW-REAL-CORP-99')
    expect(getWecomMonitor).not.toHaveBeenCalledWith('MOCK-CORP-001')

    const cards = wrapper.findAll('.proto-stat-card .number').map((n) => n.text())
    expect(cards[0]).toBe('1 位')
    expect(cards[1]).toBe('1 位')

    // 切换为仅看超过 24 小时未回复，且搜索 null 不应匹配到员工回复会话
    await wrapper.get('[data-testid="wecom-monitor-overdue"]').findComponent({ name: 'ElSelect' }).vm.$emit('update:modelValue', true)
    await flushPromises()
    expect(wrapper.findAll('.el-table__row')).toHaveLength(1)
    expect(wrapper.text()).toContain('客户 #101')
    expect(wrapper.text()).not.toContain('客户 #102')

    const kwRoot = wrapper.get('[data-testid="wecom-monitor-keyword"]')
    const kwInput = kwRoot.element.matches('input') ? kwRoot : kwRoot.get('input')
    await kwInput.setValue('null')
    await flushPromises()
    expect(wrapper.findAll('.el-table__row')).toHaveLength(0)
  })

  it('无 wecom:read 权限或机构用户不请求任何企微监管接口，接口异常展示错误提示', async () => {
    const denied = mountView([])
    await flushPromises()
    expect(denied.find('[data-testid="wecom-monitor-permission-error"]').exists()).toBe(true)
    expect(listWecomMessages).not.toHaveBeenCalled()
    expect(getWecomMonitor).not.toHaveBeenCalled()

    vi.mocked(listWecomMessages).mockRejectedValueOnce(new Error('企微接口不可用'))
    const errored = mountView(['wecom:read'])
    await flushPromises()
    expect(errored.get('[data-testid="wecom-monitor-error"]').text()).toContain('企微接口不可用')
  })
})
