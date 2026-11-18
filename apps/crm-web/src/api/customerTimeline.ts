import type { ApiResponse } from '../types/api'
import { http } from './http'

export type CustomerTimelineType =
  | 'FOLLOWUP' | 'INTENT' | 'BLOCKER' | 'NEXT_FOLLOWUP' | 'TASK' | 'OWNERSHIP'
  | 'ORDER' | 'APPOINTMENT' | 'REDEMPTION' | 'REFUND' | 'TIER' | 'COMPLAINT'

export interface CustomerTimelineItem {
  type: CustomerTimelineType
  eventKey: string
  sourceId: string
  occurredAt: string
  title: string
  summary: string | null
  details: Record<string, unknown>
  actorUserId: number | null
}

export interface CustomerTimelinePage {
  items: CustomerTimelineItem[]
  nextCursor: string | null
}

export async function getCustomerTimeline(
  customerId: number,
  query: { size: number; cursor?: string },
): Promise<CustomerTimelinePage> {
  const res = await http.get<ApiResponse<CustomerTimelinePage>>(`/customers/${customerId}/timeline`, {
    params: { size: query.size, cursor: query.cursor || undefined },
  })
  return res.data.data
}
