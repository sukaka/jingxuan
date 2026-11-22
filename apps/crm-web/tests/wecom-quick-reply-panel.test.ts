import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { listQuickReplyCategories, listQuickReplies, listQuickReplyVariables, previewQuickReply, recordQuickReplyUsage, setQuickReplyFavorite } from '../src/api/quickReply'
import type { QuickReplyUsage } from '../src/api/quickReply'
import { sendTextToCurrentChat } from '../src/services/wecomSdk'
import { copyTextWithFallback } from '../src/utils/clipboard'
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
vi.mock('../src/utils/clipboard', () => ({ copyTextWithFallback: vi.fn() }))
vi.mock('../src/stores/auth', () => ({ useAuthStore: () => ({ hasPermission: () => true }) }))

const wrappers: VueWrapper[] = []
const category = { id: 5, parentId: null, name: '项目咨询', code: 'PROJECT', scopeType: 'GLOBAL' as const, scopeId: null, sortNo: 1, status: 'ACTIVE' as const, createdBy: null, createdAt: '', updatedAt: '', version: 0 }
const greetingCategory = { id: 6, parentId: null, name: '日常问候', code: 'GREETING', scopeType: 'GLOBAL' as const, scopeId: null, sortNo: 2, status: 'ACTIVE' as const, createdBy: null, createdAt: '', updatedAt: '', version: 0 }

const template = { id: 1, categoryId: 5, name: '剩余次数说明', code: 'PROJECT_REMAINING', messageType: 'TEXT' as const, content: '还剩{remaining_count}次', scene: 'PACKAGE_REMAINING', scopeType: 'GLOBAL' as const, scopeId: null, allowEdit: true, requirePreview: true, aiEnabled: false, featured: false, favorite: false, lastUsedAt: null, usageCount: 0, status: 'ACTIVE' as const, sortNo: 1, createdBy: null, createdAt: '', updatedAt: '', version: 0 }
const staticTemplate = { id: 2, categoryId: 6, name: '到院指引通用话术', code: 'GUIDE_STATIC', messageType: 'TEXT' as const, content: '您好，到院前请提前 15 分钟签到，如需改期可随时联系管家。', scene: 'GENERAL', scopeType: 'GLOBAL' as const, scopeId: null, allowEdit: true, requirePreview: false, aiEnabled: false, featured: true, favorite: true, lastUsedAt: '2026-11-20T09:00:00Z', usageCount: 3, status: 'ACTIVE' as const, sortNo: 2, createdBy: null, createdAt: '', updatedAt: '', version: 0 }
const recentTemplate = { id: 3, categoryId: 5, name: '术后补水提醒', code: 'POST_CARE', messageType: 'TEXT' as const, content: '术后 48 小时请注意温和洁面与保湿防晒。', scene: 'AFTER_CARE', scopeType: 'GLOBAL' as const, scopeId: null, allowEdit: true, requirePreview: false, aiEnabled: false, featured: false, favorite: false, lastUsedAt: '2026-11-21T10:00:00Z', usageCount: 1, status: 'ACTIVE' as const, sortNo: 3, createdBy: null, createdAt: '', updatedAt: '', version: 0 }
const sameTimeMoreUsedTemplate = { id: 4, categoryId: 6, name: '复诊温馨提醒', code: 'FOLLOW_UP', messageType: 'TEXT' as const, content: '欢迎本周预约复诊检查。', scene: 'FOLLOW_UP', scopeType: 'GLOBAL' as const, scopeId: null, allowEdit: true, requirePreview: false, aiEnabled: false, featured: false, favorite: false, lastUsedAt: '2026-11-21T10:00:00Z', usageCount: 7, status: 'ACTIVE' as const, sortNo: 4, createdBy: null, createdAt: '', updatedAt: '', version: 0 }

async function mountPanel(
  chatEntry = 'single_chat_tools',
  overrides: Partial<{ customerId: number | null; externalUserid: string; chatId: string }> = {},
) {
  const wrapper = mount(WecomQuickReplyPanel, {
    props: {
      customerId: 'customerId' in overrides ? overrides.customerId : 1010,
      externalUserid: 'externalUserid' in overrides ? overrides.externalUserid : 'external-1001',
      chatId: overrides.chatId,
      chatEntry,
    },
  })
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
    vi.mocked(copyTextWithFallback).mockResolvedValue(undefined)
  })

  afterEach(() => {
    while (wrappers.length) wrappers.pop()?.unmount()
  })

  it('预览后可编辑并调用兼容复制工具，使用日志保存最终内容', async () => {
    const wrapper = await mountPanel()
    await wrapper.get('[data-testid="quick-reply-template-1"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="quick-reply-draft"]').setValue('编辑后还剩6次')
    await wrapper.get('[data-testid="quick-reply-copy"]').trigger('click')
    await flushPromises()

    expect(copyTextWithFallback).toHaveBeenCalledWith('编辑后还剩6次')
    expect(recordQuickReplyUsage).toHaveBeenCalledWith(1, expect.objectContaining({
      customerId: 1010, externalUserid: 'external-1001', scene: 'PACKAGE_REMAINING',
      originalContent: '还剩6次', finalContent: '编辑后还剩6次', edited: true, sendStatus: 'COPIED',
    }), expect.any(String))
  })

  it('当前单聊发送携带 expectedExternalUserid 并记录 SDK_SUCCESS', async () => {
    const wrapper = await mountPanel()
    await wrapper.get('[data-testid="quick-reply-template-1"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="quick-reply-send"]').trigger('click')
    await flushPromises()

    expect(sendTextToCurrentChat).toHaveBeenCalledWith('还剩6次', { expectedExternalUserid: 'external-1001' })
    expect(recordQuickReplyUsage).toHaveBeenCalledWith(1, expect.objectContaining({ sendStatus: 'SDK_SUCCESS' }), expect.any(String))
    expect(wrapper.text()).toContain('企微已确认发送')
  })

  it('发送前发现会话切换 mismatch 时阻止记录并抛出 context-mismatch 事件', async () => {
    vi.mocked(sendTextToCurrentChat).mockResolvedValueOnce({
      status: 'SDK_FAILED',
      mismatch: true,
      clientResult: 'sendChatMessage:fail context_mismatch: 当前聊天客户已切换',
    })
    const wrapper = await mountPanel()
    await wrapper.get('[data-testid="quick-reply-template-1"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="quick-reply-send"]').trigger('click')
    await flushPromises()

    expect(wrapper.emitted('context-mismatch')).toHaveLength(1)
    expect(recordQuickReplyUsage).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('已阻止误发')
  })

  it('支持关键词搜索跨分类过滤，以及全部 / 我的收藏 / 最近常用排序', async () => {
    vi.mocked(listQuickReplyCategories).mockResolvedValue([category, greetingCategory])
    vi.mocked(listQuickReplies).mockResolvedValue([template, staticTemplate, recentTemplate, sameTimeMoreUsedTemplate])
    const wrapper = await mountPanel()

    await wrapper.get('[data-testid="quick-reply-search"]').setValue('保湿防晒')
    expect(wrapper.find('[data-testid="quick-reply-template-3"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="quick-reply-template-1"]').exists()).toBe(false)

    await wrapper.get('[data-testid="quick-reply-search"]').setValue('到院指引')
    expect(wrapper.find('[data-testid="quick-reply-template-2"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="quick-reply-template-3"]').exists()).toBe(false)

    await wrapper.get('[data-testid="quick-reply-search"]').setValue('')
    await wrapper.get('[data-testid="quick-reply-filter-favorite"]').trigger('click')
    expect(wrapper.find('[data-testid="quick-reply-template-2"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="quick-reply-template-1"]').exists()).toBe(false)

    await wrapper.get('[data-testid="quick-reply-filter-recent"]').trigger('click')
    const orderedIds = wrapper.findAll('.template-select').map((node) => node.attributes('data-testid'))
    expect(orderedIds).toEqual([
      'quick-reply-template-4',
      'quick-reply-template-3',
      'quick-reply-template-2',
    ])
  })

  it('群聊未选客户模式允许选用编辑并发送静态话术，禁用含客户变量话术', async () => {
    vi.mocked(listQuickReplyCategories).mockResolvedValue([category, greetingCategory])
    vi.mocked(listQuickReplies).mockResolvedValue([template, staticTemplate])
    const wrapper = await mountPanel('group_chat_tools', { customerId: null, externalUserid: '', chatId: 'wr-group-001' })

    expect(wrapper.get('[data-testid="quick-reply-group-hint"]').text()).toContain('群聊模式')

    await wrapper.get('[data-testid="quick-reply-template-1"]').trigger('click')
    await flushPromises()
    expect(previewQuickReply).not.toHaveBeenCalled()
    expect(wrapper.get('[data-testid="missing-variables"]').text()).toContain('剩余次数')
    expect(wrapper.get('[data-testid="quick-reply-send"]').attributes('disabled')).toBeDefined()

    await wrapper.get('[data-testid="quick-reply-category-6"]').trigger('click')
    await wrapper.get('[data-testid="quick-reply-template-2"]').trigger('click')
    await flushPromises()
    expect(previewQuickReply).not.toHaveBeenCalled()
    await wrapper.get('[data-testid="quick-reply-draft"]').setValue('群聊通用指引：请提前 15 分钟签到。')
    await wrapper.get('[data-testid="quick-reply-send"]').trigger('click')
    await flushPromises()

    expect(sendTextToCurrentChat).toHaveBeenCalledWith('群聊通用指引：请提前 15 分钟签到。', { expectedChatId: 'wr-group-001' })
    expect(recordQuickReplyUsage).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('发送到当前群聊')
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
