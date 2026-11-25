import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { Router } from 'vue-router'
import { listNotifications, markNotificationRead } from '../src/api/notification'
import type { NotificationItem } from '../src/api/notification'
import { ApiError } from '../src/types/api'
import NotificationsView from '../src/views/NotificationsView.vue'

vi.mock('../src/api/notification', () => ({
  listNotifications: vi.fn(),
  markNotificationRead: vi.fn(),
}))

const wrappers: VueWrapper[] = []

function notification(overrides: Partial<NotificationItem> = {}): NotificationItem {
  return {
    id: 1,
    version: 4,
    notificationType: 'FOLLOWUP_REMINDER',
    title: '今日跟进提醒',
    contentMasked: '客户陈*有待跟进事项',
    sourceType: 'FOLLOWUP',
    sourceId: 8,
    deepLink: '/customers?customerId=42&from=notification',
    readAt: null,
    createdAt: '2026-11-11T01:00:00Z',
    deliveries: [{
      channel: 'WECOM_APP',
      status: 'FAILED',
      lastErrorMasked: '企微返回 errcode=***',
      providerMessageId: null,
    }],
    ...overrides,
  }
}

function page(items: NotificationItem[], overrides: { total?: number; page?: number; size?: number } = {}) {
  return {
    items,
    total: overrides.total ?? items.length,
    page: overrides.page ?? 1,
    size: overrides.size ?? 20,
  }
}

function createTestRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/notifications', name: 'notifications', component: NotificationsView },
      { path: '/customers', name: 'customers', component: { template: '<div>客户中心</div>' } },
      { path: '/followups', name: 'followups', component: { template: '<div>跟进</div>' } },
    ],
  })
}

async function mountView() {
  const router = createTestRouter()
  await router.push('/notifications')
  await router.isReady()
  const wrapper = mount(NotificationsView, { global: { plugins: [router] } })
  wrappers.push(wrapper)
  await flushPromises()
  return { wrapper, router }
}

beforeEach(() => {
  vi.mocked(listNotifications).mockReset()
  vi.mocked(markNotificationRead).mockReset()
})

afterEach(() => {
  while (wrappers.length) wrappers.pop()?.unmount()
})

describe('NotificationsView 通知中心', () => {
  it('渲染脱敏内容、来源、投递明细并支持本人通知分页', async () => {
    vi.mocked(listNotifications)
      .mockResolvedValueOnce(page([notification()], { total: 41 }))
      .mockResolvedValueOnce(page([notification({ id: 2, title: '第二页通知' })], { total: 41, page: 2 }))
    const { wrapper } = await mountView()

    expect(listNotifications).toHaveBeenNthCalledWith(1, { page: 1, size: 20 })
    expect(wrapper.text()).toContain('今日跟进提醒')
    expect(wrapper.text()).toContain('客户陈*有待跟进事项')
    expect(wrapper.text()).toContain('客户跟进 #8')
    expect(wrapper.text()).toContain('企微应用')
    expect(wrapper.text()).toContain('企微返回 errcode=***')

    await wrapper.get('[data-testid="next-page"]').trigger('click')
    await flushPromises()
    expect(listNotifications).toHaveBeenNthCalledWith(2, { page: 2, size: 20 })
    expect(wrapper.text()).toContain('第二页通知')
  })

  it('无通知时展示引导空态', async () => {
    vi.mocked(listNotifications).mockResolvedValue(page([]))
    const { wrapper } = await mountView()

    expect(wrapper.get('[role="status"]').text()).toContain('暂时没有通知')
    expect(wrapper.text()).toContain('新的业务提醒会显示在这里')
  })

  it('加载失败展示真实错误而非空态，并允许重试', async () => {
    vi.mocked(listNotifications)
      .mockRejectedValueOnce(new ApiError({ status: 403, code: 'FORBIDDEN', message: '无权查看通知' }))
      .mockResolvedValueOnce(page([notification()]))
    const { wrapper } = await mountView()

    expect(wrapper.get('[role="alert"]').text()).toContain('通知加载失败')
    expect(wrapper.get('[role="alert"]').text()).toContain('无权查看通知')
    expect(wrapper.text()).not.toContain('暂时没有通知')

    await wrapper.get('[data-testid="retry-load"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('今日跟进提醒')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('已读按钮防止双击重复提交，成功后只推进到已读', async () => {
    vi.mocked(listNotifications).mockResolvedValue(page([notification()]))
    let resolveRead!: (value: NotificationItem) => void
    vi.mocked(markNotificationRead).mockReturnValue(new Promise((resolve) => { resolveRead = resolve }))
    const { wrapper } = await mountView()

    const button = wrapper.get('[data-testid="mark-read-1"]')
    await button.trigger('click')
    await button.trigger('click')
    expect(markNotificationRead).toHaveBeenCalledTimes(1)
    expect(markNotificationRead).toHaveBeenCalledWith(1, 4, expect.any(String))

    resolveRead(notification({ readAt: '2026-11-11T02:00:00Z' }))
    await flushPromises()
    expect(wrapper.find('[data-testid="mark-read-1"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="notification-1"]').text()).toContain('已读')
    expect(wrapper.text()).not.toContain('标记为已读失败')
  })

  it('已读失败后允许用同一版本和幂等键重试，不制造第二个逻辑操作', async () => {
    vi.mocked(listNotifications).mockResolvedValue(page([notification()]))
    vi.mocked(markNotificationRead)
      .mockRejectedValueOnce(new ApiError({ status: 0, code: 'NETWORK_ERROR', message: '网络中断' }))
      .mockResolvedValueOnce(notification({ readAt: '2026-11-11T02:00:00Z' }))
    const { wrapper } = await mountView()

    await wrapper.get('[data-testid="mark-read-1"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('标记为已读失败')

    await wrapper.get('[data-testid="mark-read-1"]').trigger('click')
    await flushPromises()
    expect(markNotificationRead).toHaveBeenCalledTimes(2)
    expect(markNotificationRead).toHaveBeenNthCalledWith(1, 1, 4, expect.any(String))
    expect(markNotificationRead).toHaveBeenNthCalledWith(2, 1, 4, expect.any(String))
    expect(vi.mocked(markNotificationRead).mock.calls[0]?.[2]).toBe(vi.mocked(markNotificationRead).mock.calls[1]?.[2])
    expect(wrapper.find('[data-testid="mark-read-1"]').exists()).toBe(false)
  })

  it('未读通知先已读再进入站内深链，已读通知深链不重复写', async () => {
    vi.mocked(listNotifications).mockResolvedValue(page([notification()]))
    vi.mocked(markNotificationRead).mockResolvedValue(notification({ readAt: '2026-11-11T02:00:00Z' }))
    const unread = await mountView()

    await unread.wrapper.get('[data-testid="open-notification-1"]').trigger('click')
    await flushPromises()
    expect(markNotificationRead).toHaveBeenCalledTimes(1)
    expect(unread.router.currentRoute.value.fullPath).toBe('/customers?customerId=42&from=notification')
    unread.wrapper.unmount()

    vi.mocked(listNotifications).mockResolvedValue(page([notification({ readAt: '2026-11-11T01:30:00Z' })]))
    vi.mocked(markNotificationRead).mockClear()
    const read = await mountView()
    await read.wrapper.get('[data-testid="open-notification-1"]').trigger('click')
    await flushPromises()
    expect(markNotificationRead).not.toHaveBeenCalled()
    expect(read.router.currentRoute.value.name).toBe('customers')
  })

  it('拒绝站外深链，且不先执行已读写操作', async () => {
    vi.mocked(listNotifications).mockResolvedValue(page([notification({ deepLink: 'https://outside.example/steal' })]))
    const { wrapper, router } = await mountView()

    await wrapper.get('[data-testid="open-notification-1"]').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('notifications')
    expect(markNotificationRead).not.toHaveBeenCalled()
    expect(wrapper.get('[role="alert"]').text()).toContain('通知链接无效')
  })
})
