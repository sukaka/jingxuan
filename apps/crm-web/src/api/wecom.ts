import type { ApiResponse, PageResult } from '../types/api'
import { http } from './http'

export interface WecomMessageView {
  id: number
  corpId: string
  msgId: string
  archiveSeq: number
  customerId: number | null
  externalUserid: string | null
  staffUserId: number | null
  conversationId: string | null
  messageType: string
  senderType: string
  sentAt: string
  contentObjectKey: string
  consentSnapshot: string
  currentConsentStatus: string
  status: string
  recalledAt: string | null
  recallMessageId: string | null
  conversationType: 'DIRECT' | 'ROOM'
  senderWecomUserid: string | null
  senderStaffUserId: number | null
  roomRosterVersion: number | null
}

export interface WecomMediaAssetView {
  id: number
  mediaKind: string
  fileName: string | null
  contentType: string
  fileSize: number | null
  status: 'PENDING' | 'DOWNLOADING' | 'RETRY' | 'STORED' | 'FAILED'
  available: boolean
}

export interface WecomContentView {
  messageRef: number
  messageType: string
  sentAt: string
  content: string
  mediaAssets?: WecomMediaAssetView[]
  recalledAt?: string | null
}

export interface WecomContact {
  id: number
  corpId: string
  externalUserid: string
  staffUserId: number
  name: string | null
  avatar: string | null
  contactType: number | null
  gender: number | null
  unionid: string | null
  remark: string | null
  description: string | null
  addWay: number | null
  channelState: string | null
  followCreatedAt: string | null
  tagSnapshotJson: string | null
  followStatus: 'ACTIVE' | 'DELETED_BY_STAFF' | 'DELETED_BY_CUSTOMER'
  customerId: number | null
  consentStatus: string
  consentEvidenceRef: string | null
  linkType: string | null
  linkReason: string | null
  createdBy: number | null
  lastSyncedAt: string | null
  version: number
}

export interface WecomMonitorView {
  corpId: string
  archiveScope: string
  lastSeq: number
  lastSuccessAt: string | null
  lastAttemptAt: string | null
  status: string
  dataGap: boolean
  gapReason: string | null
  messageCount: number
  unmatchedCount: number
  activeGaps: number
  historicalGaps: number
}

export interface WecomSyncInput {
  corpId: string
  cursor: number
  limit: number
}

export interface WecomSyncResponse {
  adapter: string
  received: number
  stored: number
  duplicates: number
  skipped: number
  lastSeq: number
  hasMore: boolean
  dataGap: boolean
  recalled: number
}

export interface WecomLinkInput {
  customerId: number
  version: number
  reason: string
}

export async function listWecomMessages(query: { customerId?: number; conversationId?: string; corpId?: string; page: number; size: number }): Promise<PageResult<WecomMessageView>> {
  const res = await http.get<ApiResponse<PageResult<WecomMessageView>>>('/integrations/wecom/messages', { params: query })
  return res.data.data
}

export async function getWecomMessageContent(id: number): Promise<WecomContentView> {
  const res = await http.get<ApiResponse<WecomContentView>>(`/integrations/wecom/messages/${id}/content`)
  return res.data.data
}

export async function listWecomContacts(): Promise<WecomContact[]> {
  const res = await http.get<ApiResponse<WecomContact[]>>('/integrations/wecom/contacts')
  return res.data.data
}

export async function getWecomMonitor(corpId: string): Promise<WecomMonitorView> {
  const res = await http.get<ApiResponse<WecomMonitorView>>('/integrations/wecom/monitor', { params: { corpId } })
  return res.data.data
}

async function postWithKey<T>(url: string, body: unknown, idempotencyKey: string): Promise<T> {
  const res = await http.post<ApiResponse<T>>(url, body, { headers: { 'Idempotency-Key': idempotencyKey } })
  return res.data.data
}

export function syncWecomArchive(body: WecomSyncInput, key: string): Promise<WecomSyncResponse> {
  return postWithKey('/integrations/wecom/sync', body, key)
}

export function linkWecomContact(id: number, body: WecomLinkInput, key: string): Promise<WecomContact> {
  return postWithKey(`/integrations/wecom/contacts/${id}/link`, body, key)
}

export interface WecomContactSyncRun {
  id: number
  corpId: string
  runType: 'FULL' | 'RECONCILE' | 'CALLBACK'
  status: 'RUNNING' | 'SUCCESS' | 'ERROR'
  cursorValue: string | null
  receivedCount: number
  upsertedCount: number
  deactivatedCount: number
  errorMasked: string | null
  startedAt: string
  finishedAt: string | null
}

export async function listWecomContactSyncRuns(): Promise<WecomContactSyncRun[]> {
  const res = await http.get<ApiResponse<WecomContactSyncRun[]>>('/integrations/wecom/contact-sync/runs')
  return res.data.data
}

export function syncWecomContacts(type: 'FULL' | 'RECONCILE', key: string): Promise<WecomContactSyncRun> {
  return postWithKey('/integrations/wecom/contact-sync', { type }, key)
}

export interface WecomSyncPolicy {
  id: number
  tenantId: number
  corpId: string
  enabled: boolean
  contactSyncEnabled: boolean
  archiveSyncEnabled: boolean
  intervalSeconds: number
  nextRunAt: string | null
  lastScheduledAt: string | null
  lastStatus: 'NEVER' | 'SUCCESS' | 'PARTIAL' | 'ERROR'
  lastError: string | null
  version: number
}

export interface WecomSyncPolicyInput {
  enabled: boolean
  contactSyncEnabled: boolean
  archiveSyncEnabled: boolean
  intervalSeconds: number
  nextRunAt?: string
  version?: number
}

export async function getWecomSyncPolicy(corpId: string): Promise<WecomSyncPolicy | null> {
  const res = await http.get<ApiResponse<WecomSyncPolicy | null>>('/integrations/wecom/sync-policy', { params: { corpId } })
  return res.data.data
}

export async function saveWecomSyncPolicy(corpId: string, body: WecomSyncPolicyInput, key: string): Promise<WecomSyncPolicy> {
  const res = await http.put<ApiResponse<WecomSyncPolicy>>('/integrations/wecom/sync-policy', body, { params: { corpId }, headers: { 'Idempotency-Key': key } })
  return res.data.data
}

export type WecomTagSyncDirection = 'PULL' | 'PUSH' | 'BIDIRECTIONAL'

export interface WecomTagMappingItem {
  crmTagCode: string
  crmTagLabel: string
  crmTagStatus: string
  wecomGroupId: string | null
  wecomGroupName: string | null
  wecomTagId: string | null
  wecomTagName: string | null
  syncStatus: 'ACTIVE' | 'UNLINKED' | 'DISABLED' | 'UNMAPPED'
  lastSyncedAt: string | null
}

export interface WecomTagMappingOverview {
  corpId: string
  adapterName: string
  officialEnabled: boolean
  items: WecomTagMappingItem[]
}

export interface UpdateWecomTagMappingInput {
  action: 'BIND' | 'UNLINK'
  wecomGroupId?: string | null
  wecomGroupName?: string | null
  wecomTagId?: string | null
  wecomTagName?: string | null
}

export interface WecomTagSyncRun {
  id: number
  corpId: string
  syncScope: 'CATALOG' | 'CUSTOMER'
  direction: WecomTagSyncDirection
  triggerSource: string
  adapterName: string
  status: 'RUNNING' | 'SUCCESS' | 'PARTIAL' | 'ERROR'
  pulledCount: number
  pushedCount: number
  skippedCount: number
  failedCount: number
  errorMasked: string | null
  startedAt: string
  finishedAt: string | null
}

export interface WecomTagPushTask {
  id: number
  corpId: string
  customerId: number
  externalUseridMasked: string
  wecomUserid: string
  addTagCount: number
  removeTagCount: number
  status: 'PENDING' | 'PROCESSING' | 'SUCCEEDED' | 'RETRYABLE' | 'FAILED'
  attempts: number
  maxAttempts: number
  triggerSource: string
  errorMasked: string | null
  nextRetryAt: string | null
  updatedAt: string
}

export interface WecomCustomerTagPushResult {
  customerId: number
  adapterName: string
  officialEnabled: boolean
  matchedContacts: number
  pushedCount: number
  skippedCount: number
  failedCount: number
  errorMasked: string | null
}

export async function getWecomTagMappings(): Promise<WecomTagMappingOverview> {
  const res = await http.get<ApiResponse<WecomTagMappingOverview>>('/integrations/wecom/tag-sync/mappings')
  return res.data.data
}

export async function updateWecomTagMapping(
  crmTagCode: string,
  body: UpdateWecomTagMappingInput,
): Promise<WecomTagMappingItem> {
  const res = await http.put<ApiResponse<WecomTagMappingItem>>(
    `/integrations/wecom/tag-sync/mappings/${encodeURIComponent(crmTagCode)}`,
    body,
  )
  return res.data.data
}

export function syncWecomTagCatalog(direction: WecomTagSyncDirection, key: string): Promise<WecomTagSyncRun> {
  return postWithKey('/integrations/wecom/tag-sync/catalog', { direction }, key)
}

export function syncWecomCustomerTags(direction: WecomTagSyncDirection, key: string): Promise<WecomTagSyncRun> {
  return postWithKey('/integrations/wecom/tag-sync/customers', { direction }, key)
}

export async function pushCustomerTagsToWecom(
  customerId: number,
  key?: string,
): Promise<WecomCustomerTagPushResult> {
  const res = await http.post<ApiResponse<WecomCustomerTagPushResult>>(
    `/integrations/wecom/tag-sync/customers/${customerId}/push`,
    undefined,
    { headers: key ? { 'Idempotency-Key': key } : undefined },
  )
  return res.data.data
}

export async function listWecomTagSyncRuns(): Promise<WecomTagSyncRun[]> {
  const res = await http.get<ApiResponse<WecomTagSyncRun[]>>('/integrations/wecom/tag-sync/runs')
  return res.data.data
}

export async function listWecomTagPushTasks(status?: string): Promise<WecomTagPushTask[]> {
  const res = await http.get<ApiResponse<WecomTagPushTask[]>>('/integrations/wecom/tag-sync/tasks', {
    params: status ? { status } : undefined,
  })
  return res.data.data
}

export async function retryWecomTagPushTask(taskId: number): Promise<WecomTagPushTask> {
  const res = await http.post<ApiResponse<WecomTagPushTask>>(`/integrations/wecom/tag-sync/tasks/${taskId}/retry`)
  return res.data.data
}
