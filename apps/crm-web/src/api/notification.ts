import type { ApiResponse, PageResult } from '../types/api'
import { http } from './http'

export type NotificationChannel = 'IN_APP' | 'WECOM_APP'

export interface NotificationDelivery {
  channel: NotificationChannel
  status: string
  lastErrorMasked: string | null
  providerMessageId: string | null
}

export interface NotificationItem {
  id: number
  version: number
  notificationType: string
  title: string
  contentMasked: string
  sourceType: string
  sourceId: string | number
  deepLink: string | null
  readAt: string | null
  createdAt: string
  deliveries: NotificationDelivery[]
}

export interface NotificationListQuery {
  page: number
  size: number
}

export interface NotificationUnreadCount {
  unreadCount: number
}

/** 服务端按当前会话限定 tenantId 与 recipientUserId，前端不可指定他人范围。 */
export async function listNotifications(query: NotificationListQuery): Promise<PageResult<NotificationItem>> {
  const res = await http.get<ApiResponse<PageResult<NotificationItem>>>('/notifications', {
    params: { page: query.page, size: query.size },
  })
  return res.data.data
}

export async function getNotificationUnreadCount(): Promise<NotificationUnreadCount> {
  const res = await http.get<ApiResponse<NotificationUnreadCount>>('/notifications/unread-count')
  return res.data.data
}

export async function markNotificationRead(
  id: number,
  version: number,
  idempotencyKey: string = crypto.randomUUID(),
): Promise<NotificationItem> {
  const res = await http.post<ApiResponse<NotificationItem>>(`/notifications/${id}/read`, { version }, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
  return res.data.data
}
