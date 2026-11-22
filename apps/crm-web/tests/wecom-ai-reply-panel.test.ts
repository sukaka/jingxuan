import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { decideAiReply, generateAiReply, listAiReplyHistory } from '../src/api/aiReply'
import type { AiReplySuggestion } from '../src/api/aiReply'
import { listQuickReplies, recordQuickReplyUsage } from '../src/api/quickReply'
import type { QuickReplyUsage } from '../src/api/quickReply'
import { sendTextToCurrentChat } from '../src/services/wecomSdk'
import { copyTextWithFallback } from '../src/utils/clipboard'
import WecomAiReplyPanel from '../src/components/WecomAiReplyPanel.vue'

vi.mock('../src/api/aiReply', () => ({
  decideAiReply: vi.fn(),
  generateAiReply: vi.fn(),
  listAiReplyHistory: vi.fn(),
}))
vi.mock('../src/api/quickReply', () => ({
  listQuickReplies: vi.fn(),
  recordQuickReplyUsage: vi.fn(),
}))
vi.mock('../src/services/wecomSdk', () => ({ sendTextToCurrentChat: vi.fn() }))
vi.mock('../src/utils/clipboard', () => ({ copyTextWithFallback: vi.fn() }))
vi.mock('../src/stores/auth', () => ({ useAuthStore: () => ({ hasPermission: () => true }) }))

const template = {
  id: 1, categoryId: 5, name: '剩余次数说明', code: 'PROJECT_REMAINING', messageType: 'TEXT' as const,
  content: '还剩{remaining_count}次', scene: 'PACKAGE_REMAINING', scopeType: 'GLOBAL' as const, scopeId: null,
  allowEdit: true, requirePreview: true, aiEnabled: true, featured: false, favorite: false, lastUsedAt: null,
  usageCount: 0, status: 'ACTIVE' as const, sortNo: 1, createdBy: null, createdAt: '', updatedAt: '', version: 0,
}
const proposed: AiReplySuggestion = {
  id: 11, templateId: 1, customerId: 1010, scene: 'PACKAGE_REMAINING', reply: '测试客户您好，还剩6次。',
  reason: '基于服务端只读事实', usedFactCodes: ['customer_name', 'remaining_count'], provider: 'mock',
  model: 'mock-reply-rules-v1', promptVersion: 'reply-v1', confidence: 0.86, status: 'PROPOSED', accepted: false,
  edited: false, finalContent: null, createdAt: '2026-11-13T01:00:00Z', decidedAt: null, version: 0,
  chatContextIncluded: false, chatContextCount: 0,
}
const proposedWithChatContext: AiReplySuggestion = {
  ...proposed,
  id: 12,
  chatContextIncluded: true,
  chatContextCount: 4,
}
const wrappers: VueWrapper[] = []

async function mountPanel(chatEntry = 'single_chat_tools') {
  const wrapper = mount(WecomAiReplyPanel, { props: { customerId: 1010, externalUserid: 'external-1001', chatEntry } })
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}

async function createCandidate(wrapper: VueWrapper) {
  await wrapper.get('[data-testid="ai-generate"]').trigger('click')
  await flushPromises()
}

async function acceptCandidate(wrapper: VueWrapper, text: string) {
  await wrapper.get('[data-testid="ai-reply-draft"]').setValue(text)
  await wrapper.get('[data-testid="ai-accept"]').trigger('click')
  await flushPromises()
}

describe('企微 AI 回复面板', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(listQuickReplies).mockResolvedValue([template, { ...template, id: 2, aiEnabled: false }])
    vi.mocked(listAiReplyHistory).mockResolvedValue([])
    vi.mocked(generateAiReply).mockResolvedValue(proposed)
    vi.mocked(decideAiReply).mockImplementation(async (id, body) => ({
      ...proposed, id, status: body.decision === 'ACCEPT' ? 'ACCEPTED' : 'REJECTED', accepted: body.decision === 'ACCEPT',
      edited: body.finalContent !== proposed.reply, finalContent: body.finalContent ?? null,
      version: 1, decidedAt: '2026-11-13T01:01:00Z',
    }))
    vi.mocked(recordQuickReplyUsage).mockResolvedValue({ id: 21, aiSuggestionId: 11 } as QuickReplyUsage)
    vi.mocked(sendTextToCurrentChat).mockResolvedValue({ status: 'SDK_UNKNOWN', clientResult: 'timeout' })
    vi.mocked(copyTextWithFallback).mockResolvedValue(undefined)
  })

  afterEach(() => {
    while (wrappers.length) wrappers.pop()?.unmount()
  })

  it('采纳前不能复制或发送，编辑采纳后调用兼容复制工具并关联原候选', async () => {
    const wrapper = await mountPanel()
    expect(vi.mocked(listQuickReplies)).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[data-testid="ai-copy"]').exists()).toBe(false)
    await createCandidate(wrapper)

    expect(wrapper.get('[data-testid="ai-reply-panel"]').text()).toContain('Mock 演示候选')
    expect(wrapper.get('[data-testid="ai-chat-context-badge"]').text()).toBe('仅基于 CRM 档案事实')
    expect(wrapper.find('[data-testid="ai-copy"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="ai-send"]').exists()).toBe(false)
    await acceptCandidate(wrapper, '测试客户您好，还剩6次。如需预约请告诉我。')

    expect(decideAiReply).toHaveBeenCalledWith(11, expect.objectContaining({
      decision: 'ACCEPT', version: 0, finalContent: '测试客户您好，还剩6次。如需预约请告诉我。',
    }), expect.any(String))
    expect(wrapper.get('[data-testid="ai-reply-draft"]').attributes('readonly')).toBeDefined()
    await wrapper.get('[data-testid="ai-copy"]').trigger('click')
    await flushPromises()
    expect(copyTextWithFallback).toHaveBeenCalledWith('测试客户您好，还剩6次。如需预约请告诉我。')
    expect(recordQuickReplyUsage).toHaveBeenCalledWith(1, expect.objectContaining({
      customerId: 1010, originalContent: proposed.reply, finalContent: '测试客户您好，还剩6次。如需预约请告诉我。',
      edited: true, sendStatus: 'COPIED', aiSuggestionId: 11,
    }), expect.any(String))
  })

  it('展示是否参考已授权存档聊天的透明提示徽章', async () => {
    vi.mocked(generateAiReply).mockResolvedValueOnce(proposedWithChatContext)
    const wrapper = await mountPanel()
    await createCandidate(wrapper)

    expect(wrapper.get('[data-testid="ai-chat-context-badge"]').text()).toBe('已参考最近 4 条已授权存档聊天')
    expect(wrapper.get('[data-testid="ai-history-12"]').text()).toContain('含 4 条存档聊天')
  })

  it('驳回需原因，不复制不发送也不记使用', async () => {
    const wrapper = await mountPanel()
    await createCandidate(wrapper)
    await wrapper.get('[data-testid="ai-show-reject"]').trigger('click')
    expect(wrapper.get('[data-testid="ai-confirm-reject"]').attributes('disabled')).toBeDefined()
    await wrapper.get('[data-testid="ai-reject-reason"]').setValue('不符合当前客户语境')
    await wrapper.get('[data-testid="ai-confirm-reject"]').trigger('click')
    await flushPromises()

    expect(decideAiReply).toHaveBeenCalledWith(11, expect.objectContaining({ decision: 'REJECT', reason: '不符合当前客户语境' }), expect.any(String))
    expect(wrapper.text()).toContain('候选已驳回')
    expect(wrapper.find('[data-testid="ai-copy"]').exists()).toBe(false)
    expect(sendTextToCurrentChat).not.toHaveBeenCalled()
    expect(recordQuickReplyUsage).not.toHaveBeenCalled()
  })

  it('仅已采纳内容可发送，携带 expectedExternalUserid 且 SDK_UNKNOWN 明确提示先核实会话', async () => {
    const wrapper = await mountPanel()
    await createCandidate(wrapper)
    await acceptCandidate(wrapper, proposed.reply)
    await wrapper.get('[data-testid="ai-send"]').trigger('click')
    await flushPromises()

    expect(sendTextToCurrentChat).toHaveBeenCalledWith(proposed.reply, { expectedExternalUserid: 'external-1001' })
    expect(recordQuickReplyUsage).toHaveBeenCalledWith(1, expect.objectContaining({
      sendStatus: 'SDK_UNKNOWN', aiSuggestionId: 11,
    }), expect.any(String))
    expect(wrapper.text()).toContain('发送结果未知')
    expect(wrapper.text()).toContain('不要直接重复发送')
  })

  it('发送遇到会话切换 mismatch 时发出 context-mismatch 并不记录伪发送', async () => {
    vi.mocked(sendTextToCurrentChat).mockResolvedValueOnce({
      status: 'SDK_FAILED',
      mismatch: true,
      clientResult: 'sendChatMessage:fail context_mismatch',
    })
    const wrapper = await mountPanel()
    await createCandidate(wrapper)
    await acceptCandidate(wrapper, proposed.reply)
    await wrapper.get('[data-testid="ai-send"]').trigger('click')
    await flushPromises()

    expect(wrapper.emitted('context-mismatch')).toHaveLength(1)
    expect(recordQuickReplyUsage).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('已阻止误发')
  })

  it('生成失败可用同一幂等键重试，不伪造候选', async () => {
    vi.mocked(generateAiReply).mockRejectedValueOnce(new Error('模型暂不可用')).mockResolvedValueOnce(proposed)
    const wrapper = await mountPanel()
    await createCandidate(wrapper)
    expect(wrapper.text()).toContain('模型暂不可用')
    expect(wrapper.find('[data-testid="ai-reply-draft"]').exists()).toBe(false)
    await createCandidate(wrapper)

    const firstKey = vi.mocked(generateAiReply).mock.calls[0]?.[1]
    const secondKey = vi.mocked(generateAiReply).mock.calls[1]?.[1]
    expect(secondKey).toBe(firstKey)
    expect((wrapper.get('[data-testid="ai-reply-draft"]').element as HTMLTextAreaElement).value).toBe(proposed.reply)
  })

  it('客户资料页不提供发送按钮，历史候选仍可人工查看并显示透明来源', async () => {
    vi.mocked(listAiReplyHistory).mockResolvedValue([{ ...proposed, status: 'ACCEPTED', accepted: true, finalContent: proposed.reply, version: 1 }])
    const wrapper = await mountPanel('contact_profile')
    await wrapper.get('[data-testid="ai-history-11"]').trigger('click')

    expect((wrapper.get('[data-testid="ai-reply-draft"]').element as HTMLTextAreaElement).value).toBe(proposed.reply)
    expect(wrapper.get('[data-testid="ai-chat-context-badge"]').text()).toBe('仅基于 CRM 档案事实')
    expect(wrapper.find('[data-testid="ai-copy"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="ai-send"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('仅本人可见')
  })
})
