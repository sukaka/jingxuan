import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessage, ElMessageBox } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuthUser } from '../src/api/auth'
import type {
  WecomTagMappingOverview,
  WecomTagPushTask,
  WecomTagSyncRun,
} from '../src/api/wecom'
import {
  getWecomMonitor,
  getWecomTagMappings,
  listWecomContacts,
  listWecomContactSyncRuns,
  listWecomMessages,
  listWecomTagPushTasks,
  listWecomTagSyncRuns,
  retryWecomTagPushTask,
  syncWecomCustomerTags,
  syncWecomTagCatalog,
  updateWecomTagMapping,
} from '../src/api/wecom'
import { useAuthStore } from '../src/stores/auth'
import { ApiError } from '../src/types/api'
import WecomArchiveAdminView from '../src/views/WecomArchiveAdminView.vue'
import WecomTagSyncPanel from '../src/views/wecom/WecomTagSyncPanel.vue'

vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<typeof import('element-plus')>()
  return {
    ...actual,
    ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn() },
    ElMessageBox: { confirm: vi.fn() },
  }
})

vi.mock('../src/api/wecom', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../src/api/wecom')>()),
  listWecomMessages: vi.fn(),
  getWecomMessageContent: vi.fn(),
  listWecomContacts: vi.fn(),
  listWecomContactSyncRuns: vi.fn(),
  getWecomMonitor: vi.fn(),
  syncWecomArchive: vi.fn(),
  syncWecomContacts: vi.fn(),
  linkWecomContact: vi.fn(),
  getWecomTagMappings: vi.fn(),
  updateWecomTagMapping: vi.fn(),
  syncWecomTagCatalog: vi.fn(),
  syncWecomCustomerTags: vi.fn(),
  pushCustomerTagsToWecom: vi.fn(),
  listWecomTagSyncRuns: vi.fn(),
  listWecomTagPushTasks: vi.fn(),
  retryWecomTagPushTask: vi.fn(),
}))

const sampleOverview: WecomTagMappingOverview = {
  corpId: 'ww-mock-corp',
  adapterName: 'MOCK_WECOM_TAG_DEV_ONLY',
  officialEnabled: false,
  items: [
    {
      crmTagCode: 'VIP',
      crmTagLabel: '核心贵宾',
      crmTagStatus: 'ACTIVE',
      wecomGroupId: 'grp-001',
      wecomGroupName: '鲸选CRM标签',
      wecomTagId: 'et-vip-01',
      wecomTagName: '核心贵宾',
      syncStatus: 'ACTIVE',
      lastSyncedAt: '2026-11-26T08:00:00Z',
    },
    {
      crmTagCode: 'TAG_SENSITIVE01',
      crmTagLabel: '敏感肌',
      crmTagStatus: 'ACTIVE',
      wecomGroupId: null,
      wecomGroupName: null,
      wecomTagId: null,
      wecomTagName: null,
      syncStatus: 'UNMAPPED',
      lastSyncedAt: null,
    },
  ],
}

const sampleRun: WecomTagSyncRun = {
  id: 301,
  corpId: 'ww-mock-corp',
  syncScope: 'CATALOG',
  direction: 'BIDIRECTIONAL',
  triggerSource: 'MANUAL',
  adapterName: 'MOCK_WECOM_TAG_DEV_ONLY',
  status: 'SUCCESS',
  pulledCount: 2,
  pushedCount: 1,
  skippedCount: 0,
  failedCount: 0,
  errorMasked: null,
  startedAt: '2026-11-26T08:00:00Z',
  finishedAt: '2026-11-26T08:00:05Z',
}

const sampleTasks: WecomTagPushTask[] = [
  {
    id: 501,
    corpId: 'ww-mock-corp',
    customerId: 11,
    externalUseridMasked: 'wm***01',
    wecomUserid: 'steward_wang',
    addTagCount: 2,
    removeTagCount: 0,
    status: 'FAILED',
    attempts: 3,
    maxAttempts: 5,
    triggerSource: 'CRM_TAG_UPDATE',
    errorMasked: '企微接口超时（已脱敏）',
    nextRetryAt: null,
    updatedAt: '2026-11-26T08:10:00Z',
  },
  {
    id: 502,
    corpId: 'ww-mock-corp',
    customerId: 12,
    externalUseridMasked: 'wm***02',
    wecomUserid: 'steward_li',
    addTagCount: 1,
    removeTagCount: 1,
    status: 'SUCCEEDED',
    attempts: 1,
    maxAttempts: 5,
    triggerSource: 'MANUAL',
    errorMasked: null,
    nextRetryAt: null,
    updatedAt: '2026-11-26T08:12:00Z',
  },
]

function makeUser(roles: string[], permissions: string[]): AuthUser {
  return {
    userId: 1,
    username: 'test-user',
    displayName: '测试员工',
    userType: 'EMPLOYEE',
    roles,
    permissions,
  }
}

const wrappers: VueWrapper[] = []

function mountPanel(user: AuthUser) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = user
  auth.initialized = true
  const wrapper = mount(WecomTagSyncPanel, {
    global: { plugins: [ElementPlus, pinia] },
    attachTo: document.body,
  })
  wrappers.push(wrapper)
  return wrapper
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(getWecomTagMappings).mockResolvedValue(sampleOverview)
  vi.mocked(listWecomTagSyncRuns).mockResolvedValue([sampleRun])
  vi.mocked(listWecomTagPushTasks).mockResolvedValue(sampleTasks)
  vi.mocked(syncWecomTagCatalog).mockResolvedValue(sampleRun)
  vi.mocked(syncWecomCustomerTags).mockResolvedValue({
    ...sampleRun,
    id: 302,
    syncScope: 'CUSTOMER',
    pulledCount: 3,
    pushedCount: 2,
  })
  vi.mocked(updateWecomTagMapping).mockResolvedValue({
    ...sampleOverview.items[0]!,
    syncStatus: 'UNLINKED',
  })
  vi.mocked(retryWecomTagPushTask).mockResolvedValue({
    ...sampleTasks[0]!,
    status: 'SUCCEEDED',
    attempts: 4,
    errorMasked: null,
  })
  vi.mocked(ElMessageBox.confirm).mockResolvedValue({ value: 'confirm', action: 'confirm' } as never)
})

afterEach(() => {
  while (wrappers.length) wrappers.pop()?.unmount()
  document.body.innerHTML = ''
})

describe('WecomTagSyncPanel 企微标签双向同步与 RBAC 隔离', () => {
  it('普通管家（仅 wecom:read）为只读视图，隐藏标签库同步、解除映射、客户批量同步与推送任务队列', async () => {
    const wrapper = mountPanel(makeUser(['MANAGER'], ['wecom:read']))
    await flushPromises()

    expect(wrapper.get('[data-testid="wecom-tag-mode-pill"]').text()).toBe('模拟演示模式')
    expect(wrapper.get('[data-testid="wecom-tag-adapter-meta"]').text()).toContain('MOCK_WECOM_TAG_DEV_ONLY')
    expect(wrapper.get('[data-testid="wecom-tag-adapter-meta"]').text()).toContain('ww-mock-corp')
    expect(wrapper.text()).toContain('绝不接入个人微信')
    expect(wrapper.text()).toContain('核心贵宾')
    expect(wrapper.text()).toContain('et-vip-01')

    expect(wrapper.find('[data-testid="wecom-tag-catalog-actions"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="wecom-tag-customer-actions"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="wecom-tag-unlink-VIP"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="wecom-tag-tasks-section"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="wecom-tag-readonly-note"]').exists()).toBe(true)
    expect(listWecomTagPushTasks).not.toHaveBeenCalled()
  })

  it('管家主管（customer:tag:manage）可执行标签库双向同步与解除映射，但不可操作客户批量同步或推送任务队列', async () => {
    const wrapper = mountPanel(makeUser(['MANAGER_LEAD'], ['customer:tag:manage']))
    await flushPromises()

    expect(wrapper.find('[data-testid="wecom-tag-catalog-actions"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="wecom-tag-customer-actions"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="wecom-tag-tasks-section"]').exists()).toBe(false)
    expect(listWecomTagPushTasks).not.toHaveBeenCalled()

    await wrapper.get('[data-testid="wecom-tag-catalog-bidirectional"]').trigger('click')
    await flushPromises()
    expect(syncWecomTagCatalog).toHaveBeenCalledWith('BIDIRECTIONAL', expect.stringContaining('wecom.tag.catalog.bidirectional:'))
    expect(ElMessage.success).toHaveBeenCalled()

    await wrapper.get('[data-testid="wecom-tag-unlink-VIP"]').trigger('click')
    await flushPromises()
    expect(updateWecomTagMapping).toHaveBeenCalledWith('VIP', { action: 'UNLINK' })
    expect(ElMessage.success).toHaveBeenCalledWith('企微标签映射已解除')
  })

  it('管理员/企微监管（wecom:read + wecom:monitor）可展示官方通道状态、触发客户标签双向同步并重试失败推送任务', async () => {
    vi.mocked(getWecomTagMappings).mockResolvedValue({
      ...sampleOverview,
      adapterName: 'OFFICIAL_WECOM_TAG',
      officialEnabled: true,
    })
    const wrapper = mountPanel(makeUser(['ADMIN'], ['wecom:read', 'wecom:monitor', 'customer:tag:manage']))
    await flushPromises()

    expect(wrapper.get('[data-testid="wecom-tag-mode-pill"]').text()).toBe('官方企微通道')
    expect(wrapper.find('[data-testid="wecom-tag-catalog-actions"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="wecom-tag-customer-actions"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="wecom-tag-tasks-section"]').exists()).toBe(true)
    expect(listWecomTagPushTasks).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('wm***01')
    expect(wrapper.text()).toContain('企微接口超时（已脱敏）')

    // 仅 FAILED/RETRYABLE 任务显示重试按钮，SUCCEEDED 不显示
    expect(wrapper.find('[data-testid="wecom-tag-task-retry-501"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="wecom-tag-task-retry-502"]').exists()).toBe(false)

    await wrapper.get('[data-testid="wecom-tag-customer-bidirectional"]').trigger('click')
    await flushPromises()
    expect(syncWecomCustomerTags).toHaveBeenCalledWith('BIDIRECTIONAL', expect.stringContaining('wecom.tag.customer.bidirectional:'))

    await wrapper.get('[data-testid="wecom-tag-task-retry-501"]').trigger('click')
    await flushPromises()
    expect(retryWecomTagPushTask).toHaveBeenCalledWith(501)
    expect(ElMessage.success).toHaveBeenCalledWith('推送任务 #501 重试成功')
  })

  it('生产关闭企微官方通道且适配器为 DISABLED 时展示“未启用”而非“模拟演示模式”', async () => {
    vi.mocked(getWecomTagMappings).mockResolvedValue({
      ...sampleOverview,
      adapterName: 'DISABLED',
      officialEnabled: false,
    })
    const wrapper = mountPanel(makeUser(['ADMIN'], ['wecom:read', 'wecom:monitor']))
    await flushPromises()

    expect(wrapper.get('[data-testid="wecom-tag-mode-pill"]').text()).toBe('未启用')
    expect(wrapper.get('[data-testid="wecom-tag-mode-pill"]').text()).not.toContain('模拟演示模式')
  })

  it('接口报错时分模块展示错误信息而不崩溃', async () => {
    vi.mocked(getWecomTagMappings).mockRejectedValue(new ApiError({ status: 503, code: 'UPSTREAM_ERROR', message: '企微标签服务暂不可用' }))
    vi.mocked(listWecomTagSyncRuns).mockRejectedValue(new ApiError({ status: 500, code: 'INTERNAL_ERROR', message: '同步批次读取异常' }))
    vi.mocked(listWecomTagPushTasks).mockRejectedValue(new ApiError({ status: 500, code: 'INTERNAL_ERROR', message: '推送队列读取异常' }))

    const wrapper = mountPanel(makeUser(['ADMIN'], ['wecom:read', 'wecom:monitor']))
    await flushPromises()

    expect(wrapper.get('[data-testid="wecom-tag-mappings-error"]').text()).toContain('企微标签服务暂不可用')
    expect(wrapper.get('[data-testid="wecom-tag-runs-error"]').text()).toContain('同步批次读取异常')
    expect(wrapper.get('[data-testid="wecom-tag-tasks-error"]').text()).toContain('推送队列读取异常')
  })

  it('WecomArchiveAdminView 对仅含 customer:tag:manage 的管家主管直接展示标签双向同步页签且不请求归档接口', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    auth.user = makeUser(['MANAGER_LEAD'], ['customer:tag:manage'])
    auth.initialized = true

    const wrapper = mount(WecomArchiveAdminView, {
      global: { plugins: [ElementPlus, pinia] },
      attachTo: document.body,
    })
    wrappers.push(wrapper)
    await flushPromises()

    expect(wrapper.find('[data-testid="wecom-forbidden"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="wecom-tag-sync-panel"]').exists()).toBe(true)
    expect(listWecomMessages).not.toHaveBeenCalled()
    expect(listWecomContacts).not.toHaveBeenCalled()
    expect(listWecomContactSyncRuns).not.toHaveBeenCalled()
    expect(getWecomMonitor).not.toHaveBeenCalled()
  })
})
