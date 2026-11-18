import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { listQuickReplyCategories, listQuickReplies, listQuickReplyVariables, previewQuickReply, recordQuickReplyUsage, setQuickReplyFavorite } from '../src/api/quickReply'
import type { QuickReplyUsage } from '../src/api/quickReply'
import { sendTextToCurrentChat } from '../src/services/wecomSdk'
import WecomQuickReplyPanel from '../src/components/WecomQuickReplyPanel.vue'

vi.mock('../src/api/quickReply', () => ({
  listQuickReplyCategories: vi.fn(),
  listQuickReplies: vi.fn(),
  listQuickReplyVariables: vi.fn(),
  previewQuickReply: vi.fn(),
  recordQuickReplyUsage: vi.fn(),
  setQuickReplyFavorite: vi.fn(),
}))
vi.mock('../src/services/wecomSdk', () => ({ sendTextToCurrentChat: vi.fn() }))
vi.mock('../src/stores/auth', () => ({ useAuthStore: () => ({ hasPermission: () => true }) }))

const wrappers: VueWrapper[] = []
const category = { id: 5, parentId: null, name: '项目咨询', code: 'PROJECT', scopeType: 'GLOBAL' as const, scopeId: null, sortNo: 1, status: 'ACTIVE' as const, createdBy: null, createdAt: '', updatedAt: '', version: 0 }
const template = { id: 1, categoryId: 5, name: '剩余次数说明', code: 'PROJECT_REMAINING', messageType: 'TEXT' as const, content: '还剩{remaining_count}次', scene: 'PACKAGE_REMAINING', scopeType: 'GLOBAL' as const, scopeId: null, allowEdit: true, requirePreview: true, aiEnabled: false, featured: false, favorite: false, lastUsedAt: null, usageCount: 0, status: 'ACTIVE' as const, sortNo: 1, createdBy: null, createdAt: '', updatedAt: '', version: 0 }

async function mountPanel(chatEntry = 'single_chat_tools') {
  const wrapper = mount(WecomQuickReplyPanel, { props: { customerId: 1010, externalUserid: 'external-1001', chatEntry } })
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}

describe('企微快捷话术面板', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(listQuickReplyCategories).mockResolvedValue([category])
    vi.mocked(listQuickReplies).mockResolvedValue([template])
    vi.mocked(listQuickReplyVariables).mockResolvedValue([{ variableCode: 'remaining_count', variableName: '剩余次数', dataSource: 'CRM_ENTITLEMENT', dataType: 'NUMBER', required: true, description: '' }])
    vi.mocked(previewQuickReply).mockResolvedValue({ templateId: 1, content: '还剩6次', variables: { remaining_count: 6 }, missingVariables: [], canSend: true, allowEdit: true })
    vi.mocked(recordQuickReplyUsage).mockResolvedValue({ id: 1 } as QuickReplyUsage)
    vi.mocked(sendTextToCurrentChat).mockResolvedValue({ status: 'SDK_SUCCESS', clientResult: 'sendChatMessage:ok' })
  })

  afterEach(() => {
    while (wrappers.length) wrappers.pop()?.unmount()
  })

  it('预览后可编辑并复制，使用日志保存最终内容', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })
    const wrapper = await mountPanel()
    await wrapper.get('[data-testid="quick-reply-template-1"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="quick-reply-draft"]').setValue('编辑后还剩6次')
    await wrapper.get('[data-testid="quick-reply-copy"]').trigger('click')
    await flushPromises()

    expect(writeText).toHaveBeenCalledWith('编辑后还剩6次')
    expect(recordQuickReplyUsage).toHaveBeenCalledWith(1, expect.objectContaining({
      customerId: 1010, externalUserid: 'external-1001', scene: 'PACKAGE_REMAINING',
      originalContent: '还剩6次', finalContent: '编辑后还剩6次', edited: true, sendStatus: 'COPIED',
    }), expect.any(String))
  })

  it('当前单聊发送后记录 SDK_SUCCESS，而不是复制成功', async () => {
    const wrapper = await mountPanel()
    await wrapper.get('[data-testid="quick-reply-template-1"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="quick-reply-send"]').trigger('click')
    await flushPromises()

    expect(sendTextToCurrentChat).toHaveBeenCalledWith('还剩6次')
    expect(recordQuickReplyUsage).toHaveBeenCalledWith(1, expect.objectContaining({ sendStatus: 'SDK_SUCCESS' }), expect.any(String))
    expect(wrapper.text()).toContain('企微已确认发送')
  })

  it('缺少必填变量时禁止复制与发送', async () => {
    vi.mocked(previewQuickReply).mockResolvedValue({ templateId: 1, content: '还剩次', variables: {}, missingVariables: ['remaining_count'], canSend: false, allowEdit: true })
    const wrapper = await mountPanel()
    await wrapper.get('[data-testid="quick-reply-template-1"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="missing-variables"]').text()).toContain('剩余次数')
    expect(wrapper.get('[data-testid="quick-reply-copy"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-testid="quick-reply-send"]').attributes('disabled')).toBeDefined()
    expect(recordQuickReplyUsage).not.toHaveBeenCalled()
    expect(sendTextToCurrentChat).not.toHaveBeenCalled()
  })

  it('客户资料页只允许复制，不展示当前会话发送按钮', async () => {
    const wrapper = await mountPanel('contact_profile')
    await wrapper.get('[data-testid="quick-reply-template-1"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="quick-reply-send"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('可复制话术')
  })

  it('个人收藏使用独立接口并刷新后端排序', async () => {
    vi.mocked(setQuickReplyFavorite).mockResolvedValue({ ...template, favorite: true })
    vi.mocked(listQuickReplies).mockResolvedValueOnce([template])
      .mockResolvedValueOnce([{ ...template, favorite: true, usageCount: 2, lastUsedAt: '2026-11-13T01:00:00Z' }])
    const wrapper = await mountPanel()
    await wrapper.get('[data-testid="quick-reply-favorite-1"]').trigger('click')
    await flushPromises()

    expect(setQuickReplyFavorite).toHaveBeenCalledWith(1, true, expect.any(String))
    expect(listQuickReplies).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('已加入个人收藏')
    expect(wrapper.text()).toContain('使用 2 次')
    expect(wrapper.text()).toContain('最近使用')
  })
})
