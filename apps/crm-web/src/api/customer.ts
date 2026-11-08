import type { ApiResponse, PageResult } from '../types/api'
import { http } from './http'

/** 客户域 API。手机号只以 mobileMasked 脱敏形态返回,前端不持有明文。 */

export interface CustomerListItem {
  id: number
  customerNo: string
  displayName: string | null
  nickname: string | null
  mobileMasked: string | null
  cityCode: string | null
  customerStatus: string | null
  tierCode: string | null
  intentLevel: string | null
  ownerStaffId: number
  ownerName: string | null
  teamId: number | null
  pointsCached: number
  lastFollowupAt: string | null
  createdAt: string
  version: number
  sourceCode?: string | null
  channelCode?: string | null
  spendingLevel?: string | null
  birthday?: string | null
  tagCodes?: string[]
  nextFollowupAt?: string | null
}

export interface CustomerIdentityItem {
  identityType: string
  sourceApp: string
  externalIdMasked: string
  status: string
}

export interface CustomerDetail extends CustomerListItem {
  sourceCode: string | null
  channelCode: string | null
  spendingLevel: string | null
  birthday: string | null
  attentionProjects: string | null
  blockerNote: string | null
  remark: string | null
  remarkName: string | null
  firstAddedAt: string | null
  firstOrderAt: string | null
  tagCodes: string[]
  identities: CustomerIdentityItem[]
  deleted: boolean
}

/** ownerId 只允许收窄当前用户数据范围(后端强制,传他人 ID 不放大) */
export interface CustomerListQuery {
  page: number
  size: number
  keyword?: string
  status?: string
  cityCode?: string
  sourceCode?: string
  channelCode?: string
  tierCode?: string
  ownerId?: number
  followupDueBefore?: string
}

/** nickname/displayName 至少其一;mobile 可空(C01);MANAGER/MANAGER_LEAD 缺省归属本人，其他角色必须选择合格管家 */
export interface CreateCustomerRequest {
  displayName?: string
  nickname?: string
  mobile?: string
  cityCode?: string
  sourceCode?: string
  channelCode?: string
  customerStatus?: string
  tierCode?: string
  intentLevel?: string
  spendingLevel?: string
  birthday?: string
  attentionProjects?: string
  blockerNote?: string
  remark?: string
  remarkName?: string
  firstAddedAt?: string
  ownerStaffId?: number
}

/** PATCH 白名单 + version 乐观锁(409=已被他人修改) */
export interface UpdateCustomerRequest {
  displayName?: string
  nickname?: string
  remarkName?: string
  mobile?: string
  cityCode?: string
  sourceCode?: string
  channelCode?: string
  customerStatus?: string
  tierCode?: string
  intentLevel?: string
  spendingLevel?: string
  birthday?: string
  attentionProjects?: string
  blockerNote?: string
  remark?: string
  version: number
}

export interface PointsLedgerItem {
  id: number
  delta: number
  reasonCode: string
  sourceType: string
  sourceRef: string
  operatorUserId: number
  createdAt: string
}

export interface CustomerPoints {
  balance: number
  ledger: PointsLedgerItem[]
}

/** 合并候选/案件:状态机 SUGGESTED→REVIEWING→CONFIRMED/REJECTED */
export interface MergeCaseItem {
  id: number
  primaryCustomerId: number
  secondaryCustomerId: number
  primarySummary: string
  secondarySummary: string
  matchEvidence: unknown
  status: string
  version: number
  createdAt: string
  reversalId?: number | null
  reversalStatus?: 'REQUESTED' | 'MANUAL_REVIEW' | 'EXECUTED' | 'REJECTED' | 'ERROR' | null
  reversalWarning?: string | null
  reversalVersion?: number | null
}

export type MergeReversalStatus = NonNullable<MergeCaseItem['reversalStatus']>

export interface MergeReversalItem {
  id: number
  mergeCaseId: number
  status: MergeReversalStatus
  warningMessage: string | null
  version: number
}

export interface MergeCaseListQuery {
  page: number
  size: number
  status?: string
}

/** 转交申请:状态机 PENDING→APPROVED→EFFECTIVE(或 REJECTED);与后端对齐 from/to 命名 */
export interface OwnershipTransferItem {
  id: number
  customerId: number
  fromOwnerId: number
  fromOwnerName: string | null
  toOwnerId: number
  toOwnerName: string | null
  fromTeamId?: number | null
  toTeamId?: number | null
  requestedByName?: string | null
  reviewedByName?: string | null
  reason: string | null
  status: string
  version: number
  createdAt: string
  effectiveAt?: string | null
}

/** 可归属管家选项(GET /customer-owner-options),按当前用户数据范围返回 */
export interface CustomerOwnerOption {
  id: number
  displayName: string
  teamId: number | null
  teamName: string | null
}

/** 原业务表 23 字段档案(GET /customers/{id}/legacy-profile);value 缺失原因看 availability */
export interface LegacyProfileField {
  fieldKey: string
  originalName: string
  originalTable: string | null
  targetField: string | null
  sourceFormula: string | null
  value: string | null
  /** AVAILABLE 已入库 / PENDING_IMPORT 待历史导入 / PENDING_DOMAIN 待订单域计算 */
  availability: string
}

export interface OwnershipHistoryItem {
  id: number
  customerId: number
  oldOwnerId: number
  newOwnerId: number
  oldOwnerName?: string | null
  newOwnerName?: string | null
  changedBy: number
  changedByName?: string | null
  reason: string | null
  effectiveAt: string | null
  createdAt: string
}

export async function listCustomers(query: CustomerListQuery): Promise<PageResult<CustomerListItem>> {
  const res = await http.get<ApiResponse<PageResult<CustomerListItem>>>('/customers', {
    params: {
      page: query.page,
      size: query.size,
      keyword: query.keyword || undefined,
      status: query.status || undefined,
      cityCode: query.cityCode || undefined,
      sourceCode: query.sourceCode || undefined,
      channelCode: query.channelCode || undefined,
      tierCode: query.tierCode || undefined,
      ownerId: query.ownerId ?? undefined,
      followupDueBefore: query.followupDueBefore || undefined,
    },
  })
  return res.data.data
}

/** Idempotency-Key 防重复建档;201 返回详情 */
export async function createCustomer(
  request: CreateCustomerRequest,
  idempotencyKey?: string,
): Promise<CustomerDetail> {
  const res = await http.post<ApiResponse<CustomerDetail>>('/customers', request, {
    headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined,
  })
  return res.data.data
}

/** 范围外返回 404,不泄露存在性 */
export async function getCustomer(id: number): Promise<CustomerDetail> {
  const res = await http.get<ApiResponse<CustomerDetail>>(`/customers/${id}`)
  return res.data.data
}

export async function updateCustomer(
  id: number,
  request: UpdateCustomerRequest,
): Promise<CustomerDetail> {
  const res = await http.patch<ApiResponse<CustomerDetail>>(`/customers/${id}`, request)
  return res.data.data
}

/** 标签全量替换,审计 before/after */
export async function updateCustomerTags(id: number, tagCodes: string[]): Promise<CustomerDetail> {
  const res = await http.put<ApiResponse<CustomerDetail>>(`/customers/${id}/tags`, { tagCodes })
  return res.data.data
}

export async function getCustomerPoints(id: number): Promise<CustomerPoints> {
  const res = await http.get<ApiResponse<CustomerPoints>>(`/customers/${id}/points`)
  return res.data.data
}

/** 追加积分流水并同事务刷新缓存;无直接改余额接口 */
export async function adjustCustomerPoints(
  id: number,
  request: { delta: number; reasonCode: string; reason?: string },
  idempotencyKey: string,
): Promise<CustomerPoints> {
  const res = await http.post<ApiResponse<CustomerPoints>>(
    `/customers/${id}/points-adjustments`,
    request,
    { headers: { 'Idempotency-Key': idempotencyKey } },
  )
  return res.data.data
}

/** GET /customer-merge-candidates 分页(customer:merge) */
export async function listMergeCandidates(
  query: MergeCaseListQuery,
): Promise<PageResult<MergeCaseItem>> {
  const res = await http.get<ApiResponse<PageResult<MergeCaseItem>>>('/customer-merge-candidates', {
    params: { page: query.page, size: query.size, status: query.status || undefined },
  })
  return res.data.data
}

/** POST /customer-merge-candidates/scan(customer:merge):按 mobile_hash/identity 扫描,幂等;返回本次新增数与候选 */
export async function scanMergeCandidates(): Promise<{ created: number; items: MergeCaseItem[] }> {
  const res = await http.post<ApiResponse<{ created: number; items: MergeCaseItem[] }>>(
    '/customer-merge-candidates/scan',
  )
  return res.data.data
}

/** POST /customer-merges {primaryCustomerId,secondaryCustomerId} 人工登记候选(生成 SUGGESTED),不允许直接确认 */
export async function createMergeCase(request: {
  primaryCustomerId: number
  secondaryCustomerId: number
}): Promise<MergeCaseItem> {
  const res = await http.post<ApiResponse<MergeCaseItem>>('/customer-merges', request)
  return res.data.data
}

/** 开始审核 SUGGESTED→REVIEWING */
export async function reviewMergeCase(id: number, version: number): Promise<MergeCaseItem> {
  const res = await http.post<ApiResponse<MergeCaseItem>>(`/customer-merges/${id}/review`, {
    version,
  })
  return res.data.data
}

/**
 * 确认合并 REVIEWING→CONFIRMED:同事务迁移 identity/tag/followup/task/积分流水到主客户、从客户 deleted=1。
 * 两客户均须在当前用户可见范围,否则 403/404。
 */
export async function confirmMergeCase(
  id: number,
  version: number,
  reason: string,
): Promise<MergeCaseItem> {
  const res = await http.post<ApiResponse<MergeCaseItem>>(`/customer-merges/${id}/confirm`, {
    version,
    reason,
  })
  return res.data.data
}

export async function rejectMergeCase(
  id: number,
  version: number,
  reason: string,
): Promise<MergeCaseItem> {
  const res = await http.post<ApiResponse<MergeCaseItem>>(`/customer-merges/${id}/reject`, {
    version,
    reason,
  })
  return res.data.data
}

export async function requestMergeReversal(
  mergeCaseId: number,
  reason: string,
  idempotencyKey: string,
): Promise<MergeReversalItem> {
  const res = await http.post<ApiResponse<MergeReversalItem>>(
    `/customer-merge-candidates/${mergeCaseId}/reversal-requests`,
    { reason },
    { headers: { 'Idempotency-Key': idempotencyKey } },
  )
  return res.data.data
}

export async function getMergeReversal(mergeCaseId: number): Promise<MergeReversalItem> {
  const res = await http.get<ApiResponse<MergeReversalItem>>('/customer-merge-reversals', {
    params: { mergeCaseId },
  })
  return res.data.data
}

export async function reviewMergeReversal(
  id: number,
  action: 'approve' | 'complete' | 'reject',
  request: { reason: string; version: number },
  idempotencyKey: string,
): Promise<MergeReversalItem> {
  const res = await http.post<ApiResponse<MergeReversalItem>>(
    `/customer-merge-reversals/${id}/${action}`,
    request,
    { headers: { 'Idempotency-Key': idempotencyKey } },
  )
  return res.data.data
}

/** 发起转交申请(生成 PENDING);同组 MANAGER_LEAD 可转本组客户,跨组仅 ADMIN */
export async function createOwnershipTransfer(
  customerId: number,
  request: { newOwnerId: number; reason: string },
): Promise<OwnershipTransferItem> {
  const res = await http.post<ApiResponse<OwnershipTransferItem>>(
    `/customers/${customerId}/ownership-transfers`,
    request,
  )
  return res.data.data
}

/** GET 同路径:该客户的转交申请列表 */
export async function listOwnershipTransfers(customerId: number): Promise<OwnershipTransferItem[]> {
  const res = await http.get<ApiResponse<OwnershipTransferItem[]>>(
    `/customers/${customerId}/ownership-transfers`,
  )
  return res.data.data
}

/** 审批通过后按状态机生效(APPROVED→EFFECTIVE),写 owner+team 并追加归属历史 */
export async function approveOwnershipTransfer(
  id: number,
  version: number,
): Promise<OwnershipTransferItem> {
  const res = await http.post<ApiResponse<OwnershipTransferItem>>(
    `/customer-ownership-transfers/${id}/approve`,
    { version },
  )
  return res.data.data
}

export async function rejectOwnershipTransfer(
  id: number,
  version: number,
  reason: string,
): Promise<OwnershipTransferItem> {
  const res = await http.post<ApiResponse<OwnershipTransferItem>>(
    `/customer-ownership-transfers/${id}/reject`,
    { version, reason },
  )
  return res.data.data
}

/** 已生效归属历史(只读,不可改) */
export async function listOwnershipHistory(customerId: number): Promise<OwnershipHistoryItem[]> {
  const res = await http.get<ApiResponse<OwnershipHistoryItem[]>>(
    `/customers/${customerId}/ownership-history`,
  )
  return res.data.data
}

/** GET /customer-owner-options:当前用户数据范围内可归属的管家选项;无权限时后端返回 403 */
export async function listCustomerOwnerOptions(): Promise<CustomerOwnerOption[]> {
  const res = await http.get<ApiResponse<CustomerOwnerOption[]>>('/customer-owner-options')
  return res.data.data
}

/** GET /customers/{id}/legacy-profile(customer:read + 范围):原业务表 23 字段逐项映射 */
export async function getCustomerLegacyProfile(id: number): Promise<LegacyProfileField[]> {
  const res = await http.get<ApiResponse<LegacyProfileField[]>>(`/customers/${id}/legacy-profile`)
  return res.data.data
}
