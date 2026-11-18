import type { ApiResponse } from '../types/api'
import { http } from './http'

export interface WecomAnalytics {
  from: string
  to: string
  fromUtc: string
  toUtcExclusive: string
  timeZone: string
  metricVersion: string
  scopeType: string
  employeeId: number | null
  generatedAt: string
  quickReply: {
    usageCount: number
    copiedCount: number
    sdkSuccessCount: number
    sdkFailedCount: number
    sdkUnknownCount: number
    editedCount: number
    aiLinkedUsageCount: number
    sdkSuccessRate: number | null
  }
  aiReply: {
    generatedCount: number
    acceptedCount: number
    rejectedCount: number
    pendingCount: number
    editedAcceptedCount: number
    mockGeneratedCount: number
    copiedCount: number
    sdkSuccessCount: number
    sdkFailedCount: number
    sdkUnknownCount: number
    cohortAcceptanceRate: number | null
  }
  conversion: {
    confirmedAppointmentCount: number
    checkedInFromConfirmedCount: number
    paidOrderCount: number
    paidCustomerCount: number
    repeatPaidCustomerCount: number
    appointmentArrivalRate: number | null
    repeatCustomerRate: number | null
  }
  unsupportedMetrics: string[]
}

export async function getWecomAnalytics(query: { from: string; to: string; employeeId?: number }): Promise<WecomAnalytics> {
  const response = await http.get<ApiResponse<WecomAnalytics>>('/reports/wecom-analytics', { params: query })
  return response.data.data
}
