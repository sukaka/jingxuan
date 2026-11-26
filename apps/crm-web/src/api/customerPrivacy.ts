import type { ApiResponse, PageResult } from '../types/api'
import { http } from './http'

export type CustomerPrivacyFieldCode = 'MOBILE' | 'ID_CARD' | 'EMAIL' | 'WECHAT_ID'

export interface PrivacyFieldSummary {
  fieldCode: CustomerPrivacyFieldCode
  label: string
  present: boolean
  maskedValue: string | null
}

export interface ResolvedApproverPreview {
  approverUserId: number
  approverName: string
  approverSource: string
}

export interface CustomerPrivacyRequestItem {
  id: number
  requestNo: string
  customerId: number
  customerNo: string
  customerName: string
  applicantUserId: number
  applicantName: string | null
  applicantTeamId: number | null
  requestedFields: CustomerPrivacyFieldCode[]
  approvedFields: CustomerPrivacyFieldCode[]
  reason: string
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELED' | 'REVOKED' | string
  effectiveStatus: 'PENDING' | 'APPROVED' | 'EXPIRED' | 'REJECTED' | 'CANCELED' | 'REVOKED' | string
  assignedApproverId: number
  assignedApproverName: string | null
  approverSource: string
  requestedDurationMinutes: number
  approvedDurationMinutes: number | null
  reviewedBy: number | null
  reviewedByName: string | null
  reviewedAt: string | null
  reviewReason: string | null
  grantStartsAt: string | null
  grantExpiresAt: string | null
  remainingSeconds: number | null
  revokedBy: number | null
  revokedByName: string | null
  revokedAt: string | null
  revokeReason: string | null
  revealCount: number
  lastRevealedAt: string | null
  createdAt: string
  version: number
}

export interface CustomerPrivacyStatus {
  customerId: number
  fields: PrivacyFieldSummary[]
  canAdminDirectReveal: boolean
  canRequestAccess: boolean
  canViewAuditLogs: boolean
  resolvedApprover: ResolvedApproverPreview | null
  pendingRequest: CustomerPrivacyRequestItem | null
  activeGrant: CustomerPrivacyRequestItem | null
  allowedDurationMinutes: number[]
}

export interface CustomerPrivacyRevealResult {
  customerId: number
  accessMode: 'ADMIN_DIRECT_REVEAL' | 'APPROVED_GRANT_REVEAL' | string
  requestId: number | null
  requestNo: string | null
  revealedFields: CustomerPrivacyFieldCode[]
  values: Partial<Record<CustomerPrivacyFieldCode, string>>
  revealedAt: string
  grantExpiresAt: string | null
  autoHideSeconds: number
  accessLogId: number
}

export interface CustomerPrivacyAccessLogItem {
  id: number
  customerId: number
  customerNo: string
  customerName: string
  actorUserId: number
  actorUsername: string | null
  actorDisplayName: string | null
  actorRoleSnapshot: string
  accessMode: 'ADMIN_DIRECT_REVEAL' | 'APPROVED_GRANT_REVEAL' | string
  requestId: number | null
  requestNo: string | null
  revealedFields: CustomerPrivacyFieldCode[]
  maskedValuesSnapshot: Record<string, string>
  reason: string
  clientIp: string | null
  userAgent: string | null
  traceId: string | null
  createdAt: string
}

export interface PrivacyRequestListQuery {
  page: number
  size: number
  view?: 'MY' | 'PENDING_APPROVAL' | 'APPROVALS'
  status?: string
  keyword?: string
  customerId?: number
}

export interface PrivacyAccessLogQuery {
  page: number
  size: number
  customerId?: number
  actorUserId?: number
  accessMode?: string
}

export const PRIVACY_FIELD_LABELS: Record<string, string> = {
  MOBILE: '手机号',
  ID_CARD: '身份证号',
  EMAIL: '邮箱',
  WECHAT_ID: '微信号',
}

export const APPROVER_SOURCE_LABELS: Record<string, string> = {
  DIRECT_SUPERVISOR: '直属上级',
  TEAM_LEADER: '团队负责人',
  TEAM_MANAGER_LEAD: '团队管家主管',
  ROLE_SUPERIOR: '部门主管',
  ADMIN_FALLBACK: '系统管理员',
}

export const ACCESS_MODE_LABELS: Record<string, string> = {
  ADMIN_DIRECT_REVEAL: '管理员按钮揭盲',
  APPROVED_GRANT_REVEAL: '主管审批后查看',
}

export const DURATION_OPTIONS = [
  { minutes: 15, label: '15 分钟' },
  { minutes: 30, label: '30 分钟（默认）' },
  { minutes: 120, label: '2 小时' },
  { minutes: 1440, label: '24 小时' },
]

export function privacyFieldLabel(code: string): string {
  return PRIVACY_FIELD_LABELS[code] ?? code
}

export function approverSourceLabel(source: string | null | undefined): string {
  if (!source) return '上级主管'
  return APPROVER_SOURCE_LABELS[source] ?? source
}

export function accessModeLabel(mode: string): string {
  return ACCESS_MODE_LABELS[mode] ?? mode
}

export function durationLabel(minutes: number | null | undefined): string {
  if (!minutes) return '—'
  if (minutes >= 60 && minutes % 60 === 0) return `${minutes / 60} 小时`
  return `${minutes} 分钟`
}

export async function getCustomerPrivacyStatus(customerId: number): Promise<CustomerPrivacyStatus> {
  const res = await http.get<ApiResponse<CustomerPrivacyStatus>>(`/customers/${customerId}/privacy/status`)
  return res.data.data
}

export async function createCustomerPrivacyRequest(
  customerId: number,
  request: { fields: CustomerPrivacyFieldCode[]; durationMinutes: number; reason: string },
  idempotencyKey?: string,
): Promise<CustomerPrivacyRequestItem> {
  const res = await http.post<ApiResponse<CustomerPrivacyRequestItem>>(
    `/customers/${customerId}/privacy/requests`,
    request,
    { headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined },
  )
  return res.data.data
}

export async function revealCustomerPrivacy(
  customerId: number,
  request: {
    fields: CustomerPrivacyFieldCode[]
    reason?: string
    requestId?: number
    mode?: 'ADMIN_DIRECT' | 'APPROVED_GRANT'
  },
): Promise<CustomerPrivacyRevealResult> {
  const res = await http.post<ApiResponse<CustomerPrivacyRevealResult>>(
    `/customers/${customerId}/privacy/reveal`,
    request,
  )
  return res.data.data
}

export async function listCustomerPrivacyRequests(
  query: PrivacyRequestListQuery,
): Promise<PageResult<CustomerPrivacyRequestItem>> {
  const res = await http.get<ApiResponse<PageResult<CustomerPrivacyRequestItem>>>('/customer-privacy-requests', {
    params: {
      page: query.page,
      size: query.size,
      view: query.view || undefined,
      status: query.status || undefined,
      keyword: query.keyword || undefined,
      customerId: query.customerId ?? undefined,
    },
  })
  return res.data.data
}

export async function cancelCustomerPrivacyRequest(
  id: number,
  version: number,
): Promise<CustomerPrivacyRequestItem> {
  const res = await http.post<ApiResponse<CustomerPrivacyRequestItem>>(
    `/customer-privacy-requests/${id}/cancel`,
    { version },
  )
  return res.data.data
}

export async function approveCustomerPrivacyRequest(
  id: number,
  request: {
    approvedFields?: CustomerPrivacyFieldCode[]
    durationMinutes?: number
    reviewReason?: string
    version: number
  },
): Promise<CustomerPrivacyRequestItem> {
  const res = await http.post<ApiResponse<CustomerPrivacyRequestItem>>(
    `/customer-privacy-requests/${id}/approve`,
    request,
  )
  return res.data.data
}

export async function rejectCustomerPrivacyRequest(
  id: number,
  request: { reviewReason: string; version: number },
): Promise<CustomerPrivacyRequestItem> {
  const res = await http.post<ApiResponse<CustomerPrivacyRequestItem>>(
    `/customer-privacy-requests/${id}/reject`,
    request,
  )
  return res.data.data
}

export async function revokeCustomerPrivacyRequest(
  id: number,
  request: { revokeReason: string; version: number },
): Promise<CustomerPrivacyRequestItem> {
  const res = await http.post<ApiResponse<CustomerPrivacyRequestItem>>(
    `/customer-privacy-requests/${id}/revoke`,
    request,
  )
  return res.data.data
}

export async function listCustomerPrivacyAccessLogs(
  query: PrivacyAccessLogQuery,
): Promise<PageResult<CustomerPrivacyAccessLogItem>> {
  const res = await http.get<ApiResponse<PageResult<CustomerPrivacyAccessLogItem>>>('/customer-privacy-access-logs', {
    params: {
      page: query.page,
      size: query.size,
      customerId: query.customerId ?? undefined,
      actorUserId: query.actorUserId ?? undefined,
      accessMode: query.accessMode || undefined,
    },
  })
  return res.data.data
}
