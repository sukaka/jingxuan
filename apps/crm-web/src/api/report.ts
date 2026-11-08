import type { ApiResponse } from '../types/api'
import { http } from './http'

export type PerformanceRole = 'MANAGER' | 'BUSINESS'

export interface PendingRatio {
  value: number | null
  status: string
  questionCode: string | null
  description: string | null
}

export interface ReportFact {
  factType: string
  sourceId: number | null
  orderId: number | null
  customerId: number | null
  campaignId: number | null
  campaignBatchId: number | null
  skuId: number | null
  eventAt: string | null
  amountFen: number | null
  quantity: number | null
  sourceSystem: string | null
  receiverType: string | null
}

export interface ReportContext {
  from: string
  to: string
  fromUtc: string
  toUtcExclusive: string
  timeZone: string
  sourceSystem: string
  receiverType: string
  metricVersion: string
  sourceWatermark: string | null
}

export interface ReportQuery {
  from: string
  to: string
  timeZone: string
  sourceSystem?: string
  receiverType?: string
  metricVersion?: string
  customerId?: number
  campaignId?: number
  campaignBatchId?: number
  channelId?: number
  staffUserId?: number
  skuId?: number
  institutionId?: number
  drillDownType?: string
  drillDownLimit?: number
  page?: number
  size?: number
}

export interface MoneyFacts {
  paidFen: number
  platformPaidFen: number
  institutionPaidFen: number
  refundFen: number
  platformRefundFen: number
  institutionRefundFen: number
  netPaidFen: number
  platformNetPaidFen: number
  institutionNetPaidFen: number
}

export interface OperationsMetrics extends MoneyFacts {
  contactCustomerCount: number
  contactEventCount: number
  paidOrderCount: number
  confirmedAppointmentCount: number
  serviceCompletedCount: number
  checkedInCustomerCount: number
  checkedInAppointmentCount: number
  redemptionBatchCount: number
  redeemedCustomerCount: number
  redeemedCount: number
  refundCount: number
}

export interface OperationsStaffRow {
  staffUserId: number | null
  staffName: string | null
  contactCustomerCount: number
  contactEventCount: number
  paidOrderCount: number
  confirmedAppointmentCount: number
  serviceCompletedCount: number
  checkedInCustomerCount: number
  checkedInAppointmentCount: number
  redemptionBatchCount: number
  redeemedCustomerCount: number
  redeemedCount: number
  paidFen: number
  conversionRates: Record<string, PendingRatio>
}

export interface OperationsInstitutionRow {
  institutionId: number | null
  institutionName: string | null
  confirmedAppointmentCount: number
  serviceCompletedCount: number
  checkedInCustomerCount: number
  checkedInAppointmentCount: number
  redemptionBatchCount: number
  redeemedCustomerCount: number
  redeemedCount: number
  pendingReconciliationFen: number | null
  pendingReconciliationStatus: string
  conversionRates: Record<string, PendingRatio>
}

export interface OperationsReport extends ReportContext {
  metrics: OperationsMetrics
  staffRows: OperationsStaffRow[]
  institutionRows: OperationsInstitutionRow[]
  conversionRates: Record<string, PendingRatio>
  drillDown: ReportFact[]
  drillDownType: string
  drillDownLimit: number
}

export interface PerformanceTotals extends MoneyFacts {
  paidOrderCount: number
  refundCount: number
}

export interface PerformanceRow extends MoneyFacts {
  userId: number | null
  displayName: string | null
  attributionStatus: string
  paidOrderCount: number
  refundCount: number
  targetFen: number | null
  targetStatus: string
  targetCompletionRate: number | null
  targetCompletionStatus: string
  commissionFen: number | null
  commissionStatus: string
  sourceWatermark: string | null
}

export interface PerformanceDailyItem {
  statDate: string
  paidOrderCount: number
  refundCount: number
  paidFen: number
  refundFen: number
  netPaidFen: number
}

export interface PerformanceReport extends ReportContext {
  performanceRole: PerformanceRole
  totals: PerformanceTotals
  items: PerformanceRow[]
  total: number
  page: number
  size: number
  targetStatus: string
  commissionStatus: string
  usageStatus: string
  teamTargetFen: number | null
  teamTargetStatus: string
  teamTargetCompletionRate: number | null
  teamTargetCompletionStatus: string
  dailyItems: PerformanceDailyItem[]
  drillDown: ReportFact[]
  drillDownType: string
  drillDownLimit: number
}

export interface CampaignTotals extends MoneyFacts {
  paidOrderCount: number
  refundCount: number
  confirmedAppointmentCount: number
  checkedInCustomerCount: number
  checkedInAppointmentCount: number
  redemptionBatchCount: number
  redeemedCount: number
  touchCustomerCount: number | null
  touchStatus: string
}

export interface CampaignAnalyticsRow extends CampaignTotals {
  campaignId: number | null
  campaignBatchId: number | null
  campaignCode: string | null
  campaignBatchCode: string | null
  campaignName: string | null
  campaignBatchName: string | null
  conversionRates: Record<string, PendingRatio>
  sourceWatermark: string | null
}

export interface CampaignAnalyticsReport extends ReportContext {
  totals: CampaignTotals
  items: CampaignAnalyticsRow[]
  total: number
  page: number
  size: number
  drillDown: ReportFact[]
  drillDownType: string
  drillDownLimit: number
}

export interface OperationsDuty {
  newCustomerCount: number | null
  pendingTaskCount: number | null
  dueFollowupCount: number | null
  overdueTaskCount: number | null
  pendingAppointmentCount: number | null
  awaitingRedemptionCount: number | null
  pendingRefundCount: number | null
}

export interface OperationsDashboard extends ReportContext {
  metrics: OperationsMetrics
  dailyItems: PerformanceDailyItem[]
  institutionRows: OperationsInstitutionRow[]
  campaignRows: CampaignAnalyticsRow[]
  duty: OperationsDuty
}

async function getReport<T>(path: string, query: object): Promise<T> {
  const res = await http.get<ApiResponse<T>>(path, { params: query })
  return res.data.data
}

export function getOperationsDashboard(query: ReportQuery): Promise<OperationsDashboard> {
  return getReport('/reports/operations-dashboard', query)
}

export function getOperationsReport(query: ReportQuery): Promise<OperationsReport> {
  return getReport('/reports/operations', query)
}

export function getPerformanceReport(query: ReportQuery & { performanceRole: PerformanceRole }): Promise<PerformanceReport> {
  return getReport('/reports/performance', query)
}

export function getCampaignAnalytics(query: ReportQuery): Promise<CampaignAnalyticsReport> {
  return getReport('/reports/campaigns', query)
}
