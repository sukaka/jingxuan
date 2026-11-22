import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { listProjects } from '../src/api/catalog'
import { createH5Card, disableH5Card, listH5Cards, recordH5CardUsage } from '../src/api/h5Card'
import type { H5Card } from '../src/api/h5Card'
import { sendH5CardToCurrentChat } from '../src/services/wecomSdk'
import { copyTextWithFallback } from '../src/utils/clipboard'
import WecomH5CardPanel from '../src/components/WecomH5CardPanel.vue'

vi.mock('../src/api/catalog', () => ({ listProjects: vi.fn() }))
vi.mock('../src/api/h5Card', () => ({
  createH5Card: vi.fn(), disableH5Card: vi.fn(), listH5Cards: vi.fn(), recordH5CardUsage: vi.fn(),
}))
vi.mock('../src/services/wecomSdk', () => ({ sendH5CardToCurrentChat: vi.fn() }))
vi.mock('../src/utils/clipboard', () => ({ copyTextWithFallback: vi.fn() }))
vi.mock('../src/stores/auth', () => ({ useAuthStore: () => ({ hasPermission: () => true }) }))

const project = { id: 101, projectCode: 'CARE', projectName: '测试护理项目', categoryCode: null, listPriceFen: 1000, status: 'ACTIVE', createdAt: '', version: 0 }
const card: H5Card = {
  id: 31, customerId: 1010, projectId: 101, projectName: project.projectName,
  title: '测试护理项目｜项目介绍', summary: '了解服务流程与预约方式', content: '公开项目介绍',
  link: 'https://crm.example.com/h5/cards/high-entropy-key', imageUrl: 'https://crm.example.com/h5-card-cover.png',
  status: 'ACTIVE', createdAt: '2026-11-13T06:00:00Z', version: 0,
}
const wrappers: VueWrapper[] = []

async function mountPanel(
  chatEntry = 'single_chat_tools',
  overrides: Partial<{ customerId: number | null; externalUserid: string; chatId: string }> = {},
) {
  const wrapper = mount(WecomH5CardPanel, {
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

async function generate(wrapper: VueWrapper) {
  await wrapper.get('[data-testid="h5-generate"]').trigger('click')
  await flushPromises()
}

describe('企微 H5 项目卡片', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(listProjects).mockResolvedValue({ items: [project], total: 1, page: 1, size: 100 })
    vi.mocked(listH5Cards).mockResolvedValue([])
    vi.mocked(createH5Card).mockResolvedValue(card)
    vi.mocked(recordH5CardUsage).mockResolvedValue(undefined)
    vi.mocked(sendH5CardToCurrentChat).mockResolvedValue({ status: 'SDK_SUCCESS', clientResult: 'sendChatMessage:ok' })
    vi.mocked(disableH5Card).mockResolvedValue({ ...card, status: 'DISABLED', version: 1 })
    vi.mocked(copyTextWithFallback).mockResolvedValue(undefined)
  })

  afterEach(() => {
    while (wrappers.length) wrappers.pop()?.unmount()
  })

  it('默认封面使用 PNG；生成后由员工手动发送并携带 expectedExternalUserid', async () => {
    const wrapper = await mountPanel()
    expect(wrapper.get('[data-testid="h5-cover-preview"]').attributes('src')).toBe('/h5-card-cover.png')
    expect(wrapper.find('[data-testid="h5-send"]').exists()).toBe(false)

    await generate(wrapper)
    expect(createH5Card).toHaveBeenCalledWith(expect.objectContaining({
      customerId: 1010, projectId: 101, externalUserid: 'external-1001', title: card.title,
    }), expect.any(String))
    expect(wrapper.get('[data-testid="h5-cover-preview"]').attributes('src')).toBe('https://crm.example.com/h5-card-cover.png')
    expect(wrapper.get('[data-testid="h5-public-preview"]').attributes('href')).toBe(card.link)
    expect(wrapper.text()).not.toContain('1010')

    await wrapper.get('[data-testid="h5-send"]').trigger('click')
    await flushPromises()

    expect(sendH5CardToCurrentChat).toHaveBeenCalledWith(card, { expectedExternalUserid: 'external-1001' })
    expect(recordH5CardUsage).toHaveBeenCalledWith(31, expect.objectContaining({
      customerId: 1010, sendStatus: 'SDK_SUCCESS', clientResult: 'sendChatMessage:ok',
    }), expect.any(String))
    expect(wrapper.text()).toContain('企微已确认 H5 卡片发送')
    expect(wrapper.text()).toContain('不代表客户已读')
  })

  it('群聊未选客户时给出清晰引导；选定关联客户后可生成并携带 expectedChatId 发送到群聊', async () => {
    const emptyGroup = await mountPanel('group_chat_tools', { customerId: null, externalUserid: '', chatId: 'wr-group-009' })
    expect(emptyGroup.get('[data-testid="h5-group-no-customer"]').text()).toContain('请先选择群内已关联的 CRM 客户')
    expect(listH5Cards).not.toHaveBeenCalled()
    expect(createH5Card).not.toHaveBeenCalled()

    const groupWithCustomer = await mountPanel('group_chat_tools', { customerId: 1010, externalUserid: 'external-1001', chatId: 'wr-group-009' })
    expect(groupWithCustomer.get('[data-testid="h5-group-send-hint"]').text()).toContain('群聊模式')
    await generate(groupWithCustomer)
    await groupWithCustomer.get('[data-testid="h5-send"]').trigger('click')
    await flushPromises()

    expect(sendH5CardToCurrentChat).toHaveBeenCalledWith(card, { expectedChatId: 'wr-group-009' })
    expect(groupWithCustomer.text()).toContain('发送到当前群聊')
  })

  it('发送时遇到会话切换 mismatch 发出 context-mismatch 并不记录伪使用', async () => {
    vi.mocked(sendH5CardToCurrentChat).mockResolvedValueOnce({
      status: 'SDK_FAILED',
      mismatch: true,
      clientResult: 'sendChatMessage:fail context_mismatch',
    })
    const wrapper = await mountPanel()
    await generate(wrapper)
    await wrapper.get('[data-testid="h5-send"]').trigger('click')
    await flushPromises()

    expect(wrapper.emitted('context-mismatch')).toHaveLength(1)
    expect(recordH5CardUsage).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('已阻止误发')
  })

  it('修改已生成内容会清除旧快照，避免表单与发送内容不一致', async () => {
    const wrapper = await mountPanel()
    await generate(wrapper)
    await wrapper.get('[data-testid="h5-title"]').setValue('新的项目介绍')
    expect(wrapper.find('[data-testid="h5-send"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="h5-public-preview"]').exists()).toBe(false)
    await generate(wrapper)
    expect(vi.mocked(createH5Card).mock.calls[1]?.[1]).not.toBe(vi.mocked(createH5Card).mock.calls[0]?.[1])
  })

  it('停用需要理由，停用后不再开放复制或发送', async () => {
    const wrapper = await mountPanel()
    await generate(wrapper)
    await wrapper.get('[data-testid="h5-show-disable"]').trigger('click')
    expect(wrapper.get('[data-testid="h5-disable"]').attributes('disabled')).toBeDefined()
    await wrapper.get('[data-testid="h5-disable-reason"]').setValue('介绍内容需要修订')
    await wrapper.get('[data-testid="h5-disable"]').trigger('click')
    await flushPromises()

    expect(disableH5Card).toHaveBeenCalledWith(31, { version: 0, reason: '介绍内容需要修订' }, expect.any(String))
    expect(wrapper.find('[data-testid="h5-send"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="h5-copy"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('公开链接已停用')
  })

  it('客户资料页仅复制并使用兼容剪贴板工具', async () => {
    const wrapper = await mountPanel('contact_profile')
    await generate(wrapper)
    expect(wrapper.find('[data-testid="h5-send"]').exists()).toBe(false)
    await wrapper.get('[data-testid="h5-copy"]').trigger('click')
    await flushPromises()
    expect(copyTextWithFallback).toHaveBeenCalledWith(card.link)
    expect(recordH5CardUsage).toHaveBeenCalledWith(31, expect.objectContaining({ sendStatus: 'COPIED' }), expect.any(String))
  })

  it('生成失败用同一幂等键重试，不创建伪卡片', async () => {
    vi.mocked(createH5Card).mockRejectedValueOnce(new Error('服务暂不可用')).mockResolvedValueOnce(card)
    const wrapper = await mountPanel()
    await generate(wrapper)
    expect(wrapper.text()).toContain('服务暂不可用')
    expect(wrapper.find('[data-testid="h5-send"]').exists()).toBe(false)
    await generate(wrapper)
    expect(vi.mocked(createH5Card).mock.calls[1]?.[1]).toBe(vi.mocked(createH5Card).mock.calls[0]?.[1])
    expect(wrapper.get('[data-testid="h5-public-preview"]').attributes('href')).toBe(card.link)
  })
})
