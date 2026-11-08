import type { ApiResponse, PageResult } from '../types/api'
import { http } from './http'

export interface YouzanOrderView {
  mapId: number | null
  shopId: string
  externalOrderNo: string
  orderId: number | null
  status: string
  paidFen: number | null
  sourceSystem: string
  externalShopId: string
  eventId: string
  mappingStatus: string
  orderStatus: string | null
  sourceUpdatedAt: string | null
  externalAmountFen: number | null
  verificationMethod: string | null
  payloadMaskedJson: string | null
  errorReason: string | null
}

export interface YouzanSyncResponse {
  received: number
  processed: number
  duplicates: number
  issues: number
  nextCursor: string | null
  hasMore: boolean
}

export interface YouzanReconcileReport {
  shopId: string
  totalEvents: number
  internalOrders: number
  openIssues: number
  lastSyncAt: string | null
  todayEvents: number
  campaignOrders: number
}

export interface YouzanSyncIssue {
  id: number
  eventId: number
  issueType: string
  status: string
  detailMasked: string | null
  attempts: number
  lastError: string | null
  nextRetryAt: string | null
  createdAt: string
  resolvedAt: string | null
  resolvedBy: number | null
  version: number
}

export interface YouzanSkuMapping {
  id: number
  shopId: string
  externalSkuId: string
  crmSkuId: number
  status: string
  createdAt: string
  version: number
}

export interface YouzanCustomerLink {
  id: number
  shopId: string
  externalCustomerId: string
  customerId: number
  linkType: string
  confidence: string
  reason: string | null
  createdBy: number
  createdAt: string
  version: number
}

export interface YouzanEventResponse {
  id: number
  eventId: string
  eventType: string
  externalOrderNo: string
  processStatus: string
  processAttempts: number
  lastError: string | null
  orderId: number | null
  receivedAt: string
  processedAt: string | null
  duplicate: boolean
}

export interface YouzanSyncInput {
  shopId: string
  cursor?: string
  limit?: number
}

export interface YouzanSkuMappingInput {
  shopId: string
  externalSkuId: string
  crmSkuId: number
  version?: number
}

export interface YouzanCustomerLinkInput {
  shopId: string
  externalCustomerId: string
  customerId: number
  version?: number
  reason: string
}

export async function listYouzanOrders(query: { shopId: string; page: number; size: number }): Promise<PageResult<YouzanOrderView>> {
  const res = await http.get<ApiResponse<PageResult<YouzanOrderView>>>('/integrations/youzan/orders', { params: query })
  return res.data.data
}

async function postWithKey<T>(url: string, body: unknown, idempotencyKey: string): Promise<T> {
  const res = await http.post<ApiResponse<T>>(url, body, { headers: { 'Idempotency-Key': idempotencyKey } })
  return res.data.data
}

export function syncYouzanOrders(body: YouzanSyncInput, key: string): Promise<YouzanSyncResponse> {
  return postWithKey('/integrations/youzan/sync', body, key)
}

export function reconcileYouzanOrders(body: YouzanSyncInput, key: string): Promise<YouzanSyncResponse> {
  return postWithKey('/integrations/youzan/reconcile', body, key)
}

export async function getYouzanReconcileReport(shopId: string): Promise<YouzanReconcileReport> {
  const res = await http.get<ApiResponse<YouzanReconcileReport>>('/integrations/youzan/reconcile-report', { params: { shopId } })
  return res.data.data
}

export async function listYouzanSyncIssues(query: { status?: string; page: number; size: number }): Promise<PageResult<YouzanSyncIssue>> {
  const res = await http.get<ApiResponse<PageResult<YouzanSyncIssue>>>('/integrations/youzan/sync-issues', { params: query })
  return res.data.data
}

export function retryYouzanSyncIssue(id: number, key: string): Promise<YouzanEventResponse> {
  return postWithKey(`/integrations/youzan/sync-issues/${id}/retry`, undefined, key)
}

export async function listYouzanSkuMappings(shopId: string): Promise<YouzanSkuMapping[]> {
  const res = await http.get<ApiResponse<YouzanSkuMapping[]>>('/integrations/youzan/sku-mappings', { params: { shopId } })
  return res.data.data
}

export function saveYouzanSkuMapping(body: YouzanSkuMappingInput, key: string): Promise<YouzanSkuMapping> {
  return postWithKey('/integrations/youzan/sku-mappings', body, key)
}

export async function listYouzanCustomerLinks(shopId: string): Promise<YouzanCustomerLink[]> {
  const res = await http.get<ApiResponse<YouzanCustomerLink[]>>('/integrations/youzan/customer-links', { params: { shopId } })
  return res.data.data
}

export function saveYouzanCustomerLink(body: YouzanCustomerLinkInput, key: string): Promise<YouzanCustomerLink> {
  return postWithKey('/integrations/youzan/customer-links', body, key)
}

export interface YouzanShopConfig {
  id: number
  shopId: string
  shopName: string
  clientId: string
  secretConfigured: boolean
  tokenExpiresAt: string | null
  connectionStatus: string
  syncEnabled: boolean
  lastError: string | null
  lastConnectedAt: string | null
  webhookPath: string
  version: number
}

export interface YouzanShopConfigInput {
  shopId: string
  shopName: string
  clientId: string
  clientSecret?: string
  syncEnabled: boolean
  version?: number
}

export interface YouzanSyncPolicy {
  id: number
  shopId: string
  initialStartTime: string
  intervalSeconds: number
  overlapSeconds: number
  pageSize: number
  enabled: boolean
  nextRunAt: string | null
  lastScheduledAt: string | null
  version: number
}

export interface YouzanSyncPolicyInput {
  initialStartTime: string
  intervalSeconds: number
  overlapSeconds: number
  pageSize: number
  enabled: boolean
  nextRunAt?: string
  version?: number
}

export interface YouzanSyncJob {
  id: number
  shopId: string
  jobType: string
  status: string
  cursorValue: string | null
  windowStart: string | null
  windowEnd: string | null
  receivedCount: number
  processedCount: number
  duplicateCount: number
  issueCount: number
  lastError: string | null
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
  version: number
}

export async function listYouzanShops(): Promise<YouzanShopConfig[]> {
  const res = await http.get<ApiResponse<YouzanShopConfig[]>>('/integrations/youzan/shops')
  return res.data.data
}

export function saveYouzanShop(body: YouzanShopConfigInput, key: string): Promise<YouzanShopConfig> {
  return postWithKey('/integrations/youzan/shops', body, key)
}

export function testYouzanShop(shopId: string, key: string): Promise<YouzanShopConfig> {
  return postWithKey(`/integrations/youzan/shops/${encodeURIComponent(shopId)}/test`, undefined, key)
}

export async function getYouzanSyncPolicy(shopId: string): Promise<YouzanSyncPolicy | null> {
  const res = await http.get<ApiResponse<YouzanSyncPolicy | null>>('/integrations/youzan/shops/' + encodeURIComponent(shopId) + '/sync-policy')
  return res.data.data
}

export async function saveYouzanSyncPolicy(shopId: string, body: YouzanSyncPolicyInput, key: string): Promise<YouzanSyncPolicy> {
  const res = await http.put<ApiResponse<YouzanSyncPolicy>>('/integrations/youzan/shops/' + encodeURIComponent(shopId) + '/sync-policy', body, { headers: { 'Idempotency-Key': key } })
  return res.data.data
}

export async function listYouzanSyncJobs(query: { shopId: string; page: number; size: number }): Promise<PageResult<YouzanSyncJob>> {
  const res = await http.get<ApiResponse<PageResult<YouzanSyncJob>>>('/integrations/youzan/sync-jobs', { params: query })
  return res.data.data
}
