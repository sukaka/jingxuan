import type { AxiosAdapter } from 'axios'
import { afterEach, describe, expect, it } from 'vitest'
import { CSRF_COOKIE_NAME, CSRF_HEADER_NAME, http } from '../src/api/http'
import {
  getNotificationUnreadCount,
  listNotifications,
  markNotificationRead,
} from '../src/api/notification'
import type { NotificationItem } from '../src/api/notification'
import type { PageResult } from '../src/types/api'

const originalAdapter = http.defaults.adapter

interface CapturedCall {
  method: string
  url: string
  data: unknown
  params: Record<string, unknown>
  headers: Record<string, unknown>
}

function captureAdapter(responseData: unknown): { adapter: AxiosAdapter; calls: CapturedCall[] } {
  const calls: CapturedCall[] = []
  const adapter: AxiosAdapter = async (config) => {
    calls.push({
      method: (config.method ?? 'get').toLowerCase(),
      url: config.url ?? '',
      data: typeof config.data === 'string' ? JSON.parse(config.data) : config.data,
      params: (config.params ?? {}) as Record<string, unknown>,
      headers: config.headers.toJSON() as Record<string, unknown>,
    })
    return {
      status: 200,
      statusText: 'OK',
      headers: {},
      config,
      data: { code: 'OK', message: 'success', data: responseData, requestId: 'req-notification', timestamp: '' },
    }
  }
  return { adapter, calls }
}

const notification: NotificationItem = {
  id: 19,
  version: 3,
  notificationType: 'FOLLOWUP_REMINDER',
  title: '今日跟进提醒',
  contentMasked: '客户陈*有待跟进事项',
  sourceType: 'FOLLOWUP',
  sourceId: 8,
  deepLink: '/followups',
  readAt: null,
  createdAt: '2026-11-11T01:00:00Z',
  deliveries: [],
}

afterEach(() => {
  http.defaults.adapter = originalAdapter
  document.cookie = `${CSRF_COOKIE_NAME}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/`
})

describe('通知 API 契约', () => {
  it('本人通知分页只传 page/size，不接受租户或收件人越权筛选参数', async () => {
    const page: PageResult<NotificationItem> = { items: [notification], total: 1, page: 2, size: 20 }
    const { adapter, calls } = captureAdapter(page)
    http.defaults.adapter = adapter

    await expect(listNotifications({ page: 2, size: 20 })).resolves.toEqual(page)

    expect(calls).toHaveLength(1)
    expect(calls[0]).toMatchObject({ method: 'get', url: '/notifications' })
    expect(calls[0]?.params).toEqual({ page: 2, size: 20 })
    expect(calls[0]?.params).not.toHaveProperty('tenantId')
    expect(calls[0]?.params).not.toHaveProperty('recipientUserId')
  })

  it('未读数读取独立 unread-count 资源并返回 unreadCount', async () => {
    const { adapter, calls } = captureAdapter({ unreadCount: 12 })
    http.defaults.adapter = adapter

    await expect(getNotificationUnreadCount()).resolves.toEqual({ unreadCount: 12 })

    expect(calls[0]).toMatchObject({ method: 'get', url: '/notifications/unread-count' })
  })

  it('已读 POST 携带版本请求体、CSRF 与指定 Idempotency-Key，并返回完整通知', async () => {
    document.cookie = `${CSRF_COOKIE_NAME}=notification-csrf-token`
    const readNotification = { ...notification, readAt: '2026-11-11T02:00:00Z' }
    const { adapter, calls } = captureAdapter(readNotification)
    http.defaults.adapter = adapter

    await expect(markNotificationRead(19, 3, 'notification-read-key')).resolves.toEqual(readNotification)

    expect(calls[0]).toMatchObject({ method: 'post', url: '/notifications/19/read', data: { version: 3 } })
    expect(calls[0]?.headers['Idempotency-Key']).toBe('notification-read-key')
    expect(calls[0]?.headers[CSRF_HEADER_NAME]).toBe('notification-csrf-token')
  })
})
