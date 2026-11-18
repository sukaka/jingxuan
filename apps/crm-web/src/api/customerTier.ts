import type { ApiResponse, PageResult } from '../types/api'
import { http } from './http'

export type CustomerTierCode = 'S' | 'A' | 'B' | 'C' | 'D'
export type CustomerTierMetricCode =
  | 'PAYMENT_AMOUNT_FEN'
  | 'PAID_AMOUNT_FEN'
  | 'REFUND_AMOUNT_FEN'
  | 'ORDER_COUNT'
  | 'COMMUNICATION_COUNT'

export interface CustomerTierLevelInput {
  tierCode: CustomerTierCode
  tierName: string
  sortNo: number
  minScore: string
  minConsumptionFen: number | null
}

export interface CustomerTierMetricInput {
  metricCode: CustomerTierMetricCode
  direction: 'GTE' | 'LTE'
  weight: string
  thresholds: { tierCode: CustomerTierCode; value: number }[]
}

export interface CustomerTierRuleRequest {
  ruleCode: string
  ruleName: string
  timezone: string
  levels: CustomerTierLevelInput[]
  metrics: CustomerTierMetricInput[]
}

export interface CustomerTierRule extends Omit<CustomerTierRuleRequest, 'levels' | 'metrics'> {
  id: number
  versionNo: number
  status: 'DRAFT' | 'ACTIVE' | 'RETIRED'
  levels: (Omit<CustomerTierLevelInput, 'minScore' | 'minConsumptionFen'> & {
    minScore: string | number | null
    minConsumptionFen: number | null
  })[]
  metrics: (Omit<CustomerTierMetricInput, 'weight'> & { weight: string | number })[]
  activatedAt: string | null
  retiredAt: string | null
  createdAt: string
  version: number
}

export interface CustomerTierEvaluation {
  snapshotId: number | null
  customerId: number
  ruleId: number
  asOf: string
  windowStartInclusive: string
  windowEndExclusive: string
  timezone: string
  paymentAmountFen: number
  paidAmountFen: number
  refundAmountFen: number
  actualConsumptionFen: number | null
  orderCount: number
  manualFollowupCount: number
  archivedMessageCount: number
  communicationCount: number | null
  communicationComplete: boolean
  score: string | null
  suggestedTierCode: CustomerTierCode | null
  sourceTrigger: string
}

export interface CustomerTierReview {
  id: number
  customerId: number
  ruleId: number
  snapshotId: number
  fromTierCode: CustomerTierCode
  toTierCode: CustomerTierCode
  baseCustomerVersion: number
  status: 'PENDING_REVIEW' | 'APPROVED' | 'REJECTED' | 'STALE'
  requestedBy: number | null
  reviewedBy: number | null
  reviewReason: string | null
  reviewedAt: string | null
  effectiveAt: string | null
  createdAt: string
  version: number
}

export interface CustomerTierHistory {
  id: number
  customerId: number
  ruleId: number | null
  snapshotId: number | null
  reviewId: number | null
  fromTierCode: CustomerTierCode | null
  toTierCode: CustomerTierCode
  changeType: 'AUTO_UPGRADE' | 'DOWNGRADE_APPROVED' | 'MANUAL_ADJUST'
  actorUserId: number | null
  reason: string | null
  effectiveAt: string
  createdAt: string
}

export interface CustomerTierView {
  customerId: number
  tierCode: CustomerTierCode | null
  locked: boolean
  lockReason: string | null
  customerVersion: number
  activeRuleId: number | null
  latestSnapshot: CustomerTierEvaluation | null
  pendingReview: CustomerTierReview | null
}

function idempotency(key: string) {
  return { headers: { 'Idempotency-Key': key } }
}

export async function listCustomerTierRules(): Promise<CustomerTierRule[]> {
  const res = await http.get<ApiResponse<CustomerTierRule[]>>('/customer-tier-rules')
  return res.data.data
}

export async function createCustomerTierRule(
  request: CustomerTierRuleRequest,
  key: string,
): Promise<CustomerTierRule> {
  const res = await http.post<ApiResponse<CustomerTierRule>>('/customer-tier-rules', request, idempotency(key))
  return res.data.data
}

export async function updateCustomerTierRule(
  id: number,
  request: CustomerTierRuleRequest,
  version: number,
  key: string,
): Promise<CustomerTierRule> {
  const res = await http.put<ApiResponse<CustomerTierRule>>(
    `/customer-tier-rules/${id}`,
    request,
    { ...idempotency(key), params: { version } },
  )
  return res.data.data
}

export async function activateCustomerTierRule(id: number, version: number, key: string): Promise<CustomerTierRule> {
  const res = await http.post<ApiResponse<CustomerTierRule>>(
    `/customer-tier-rules/${id}/activation`, { version }, idempotency(key),
  )
  return res.data.data
}

export async function retireCustomerTierRule(id: number, version: number, key: string): Promise<CustomerTierRule> {
  const res = await http.post<ApiResponse<CustomerTierRule>>(
    `/customer-tier-rules/${id}/retirement`, { version }, idempotency(key),
  )
  return res.data.data
}

export async function trialCustomerTierRule(
  id: number,
  customerId: number,
  asOf?: string,
): Promise<CustomerTierEvaluation> {
  const res = await http.post<ApiResponse<CustomerTierEvaluation>>(`/customer-tier-rules/${id}/trial`, {
    customerId,
    asOf: asOf || undefined,
  })
  return res.data.data
}

export async function sampleCustomerTierRule(id: number, limit = 20): Promise<CustomerTierEvaluation[]> {
  const res = await http.get<ApiResponse<CustomerTierEvaluation[]>>(`/customer-tier-rules/${id}/samples`, {
    params: { limit },
  })
  return res.data.data
}

export async function getCustomerTier(customerId: number): Promise<CustomerTierView> {
  const res = await http.get<ApiResponse<CustomerTierView>>(`/customers/${customerId}/tier`)
  return res.data.data
}

export async function recalculateCustomerTier(
  customerId: number,
  version: number,
  key: string,
  asOf?: string,
): Promise<CustomerTierEvaluation> {
  const res = await http.post<ApiResponse<CustomerTierEvaluation>>(
    `/customers/${customerId}/tier-recalculations`,
    { version, asOf: asOf || undefined },
    idempotency(key),
  )
  return res.data.data
}

export async function adjustCustomerTier(
  customerId: number,
  request: { tierCode: CustomerTierCode; reason: string; lock: boolean; version: number },
  key: string,
): Promise<CustomerTierView> {
  const res = await http.post<ApiResponse<CustomerTierView>>(
    `/customers/${customerId}/tier-adjustments`, request, idempotency(key),
  )
  return res.data.data
}

export async function listCustomerTierHistory(customerId: number): Promise<CustomerTierHistory[]> {
  const res = await http.get<ApiResponse<CustomerTierHistory[]>>(`/customers/${customerId}/tier-history`)
  return res.data.data
}

export async function listCustomerTierSnapshots(customerId: number): Promise<CustomerTierEvaluation[]> {
  const res = await http.get<ApiResponse<CustomerTierEvaluation[]>>(`/customers/${customerId}/tier-metric-snapshots`)
  return res.data.data
}

export async function listCustomerTierReviews(query: {
  status?: string
  page: number
  size: number
}): Promise<PageResult<CustomerTierReview>> {
  const res = await http.get<ApiResponse<PageResult<CustomerTierReview>>>('/customer-tier-reviews', {
    params: { status: query.status || undefined, page: query.page, size: query.size },
  })
  return res.data.data
}

export async function reviewCustomerTierDowngrade(
  id: number,
  action: 'approve' | 'reject',
  request: { reason: string; version: number },
  key: string,
): Promise<CustomerTierReview> {
  const res = await http.post<ApiResponse<CustomerTierReview>>(
    `/customer-tier-reviews/${id}/${action}`, request, idempotency(key),
  )
  return res.data.data
}
