import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { Router } from 'vue-router'
import { getNotificationUnreadCount } from '../src/api/notification'
import NotificationBell from '../src/components/NotificationBell.vue'
import { ApiError } from '../src/types/api'

vi.mock('../src/api/notification', () => ({
  getNotificationUnreadCount: vi.fn(),
}))

const wrappers: VueWrapper[] = []

function createTestRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div>工作台</div>' } },
      { path: '/notifications', name: 'notifications', component: { template: '<div>通知中心</div>' } },
    ],
  })
}

async function mountBell() {
  const router = createTestRouter()
  await router.push('/')
  await router.isReady()
  const wrapper = mount(NotificationBell, { global: { plugins: [router] } })
  wrappers.push(wrapper)
  await flushPromises()
  return { wrapper, router }
}

beforeEach(() => {
  vi.useFakeTimers()
  vi.mocked(getNotificationUnreadCount).mockReset()
})

afterEach(() => {
  while (wrappers.length) wrappers.pop()?.unmount()
  vi.clearAllTimers()
  vi.useRealTimers()
})

describe('NotificationBell 未读轮询', () => {
  it('挂载立即读取，每 30 秒刷新未读数，卸载后停止轮询', async () => {
    vi.mocked(getNotificationUnreadCount)
      .mockResolvedValueOnce({ unreadCount: 2 })
      .mockResolvedValueOnce({ unreadCount: 9 })
    const { wrapper } = await mountBell()

    expect(getNotificationUnreadCount).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[data-testid="notification-unread-count"]').text()).toBe('2')

    await vi.advanceTimersByTimeAsync(30_000)
    await flushPromises()
    expect(getNotificationUnreadCount).toHaveBeenCalledTimes(2)
    expect(wrapper.get('[data-testid="notification-unread-count"]').text()).toBe('9')
    expect(wrapper.get('button').attributes('aria-label')).toContain('9 条未读')

    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(60_000)
    expect(getNotificationUnreadCount).toHaveBeenCalledTimes(2)
  })

  it('轮询失败保留可用状态并提示，下一次成功刷新后恢复', async () => {
    vi.mocked(getNotificationUnreadCount)
      .mockRejectedValueOnce(new ApiError({ status: 0, code: 'NETWORK_ERROR', message: '网络中断' }))
      .mockResolvedValueOnce({ unreadCount: 5 })
    const { wrapper } = await mountBell()

    expect(wrapper.find('[data-testid="notification-unread-count"]').exists()).toBe(false)
    expect(wrapper.get('button').attributes('aria-label')).toContain('未读数暂不可用')

    await vi.advanceTimersByTimeAsync(30_000)
    await flushPromises()
    expect(wrapper.get('[data-testid="notification-unread-count"]').text()).toBe('5')
    expect(wrapper.get('button').attributes('aria-label')).not.toContain('暂不可用')
  })

  it('已读变更事件触发即时刷新，点击铃铛进入通知中心', async () => {
    vi.mocked(getNotificationUnreadCount)
      .mockResolvedValueOnce({ unreadCount: 3 })
      .mockResolvedValueOnce({ unreadCount: 2 })
    const { wrapper, router } = await mountBell()

    window.dispatchEvent(new Event('crm:notifications-changed'))
    await flushPromises()
    expect(getNotificationUnreadCount).toHaveBeenCalledTimes(2)
    expect(wrapper.get('[data-testid="notification-unread-count"]').text()).toBe('2')

    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.name).toBe('notifications')
  })
})
