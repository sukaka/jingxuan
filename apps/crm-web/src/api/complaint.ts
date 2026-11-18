import type { ApiResponse, PageResult } from '../types/api'
import { http } from './http'

export type ComplaintHitStatus = 'OPEN' | 'CONVERTED' | 'DISMISSED'
export type ComplaintCaseStatus = 'OPEN' | 'ASSIGNED' | 'RESOLVED' | 'CLOSED' | 'DISMISSED'

export interface ComplaintPolicy {
  id: number
  enabled: boolean
  fallbackRecipientUserId: number | null
  empathyScript: string
  createdAt: string
  updatedAt: string
  version: number
}

export interface ComplaintEmpathyScript {
  id: number
  title: string
  script: string
  status: 'ACTIVE' | 'DISABLED'
  createdBy: number | null
  createdAt: string
  updatedAt: string
  version: number
}

export interface CreateComplaintEmpathyScriptRequest {
  title: string
  script: string
  reason: string
}

export interface UpdateComplaintEmpathyScriptRequest extends ComplaintReasonRequest {
  title: string
  script: string
}

export interface ComplaintKeyword {
  id: number
  keyword: string
  status: 'ACTIVE' | 'DISABLED'
  createdBy: number | null
  createdAt: string
  updatedAt: string
  version: number
}

export interface ComplaintHit {
  id: number
  customerId: number
  ownerStaffIdSnapshot: number
  teamIdSnapshot: number | null
  sourceType: 'WECOM_MESSAGE' | 'FOLLOWUP'
  sourceId: string
  keywordId: number | null
  keywordSnapshot: string
  evidenceMasked: string
  status: ComplaintHitStatus
  routingStatus: 'ROUTED' | 'EXCEPTION'
  routingError: string | null
  recipientUserIdSnapshot: number | null
  recipientSourceSnapshot: string
  policyVersionSnapshot: number
  complaintCaseId: number | null
  reviewReasonMasked: string | null
  reviewedBy: number | null
  reviewedAt: string | null
  createdAt: string
  updatedAt: string
  version: number
}

export interface ComplaintCase {
  id: number
  complaintCaseNo: string
  complaintHitId: number
  customerId: number
  ownerStaffIdSnapshot: number
  teamIdSnapshot: number | null
  recipientUserIdSnapshot: number | null
  recipientSourceSnapshot: string
  status: ComplaintCaseStatus
  assigneeUserId: number | null
  resolutionMasked: string | null
  resolvedAt: string | null
  closedAt: string | null
  dismissReasonMasked: string | null
  createdBy: number | null
  createdAt: string
  updatedAt: string
  version: number
}

export interface ComplaintHitHistory {
  id: number
  complaintHitId: number
  action: string
  fromStatus: string | null
  toStatus: string
  actorUserId: number | null
  reasonMasked: string | null
  createdAt: string
}

export interface ComplaintCaseHistory {
  id: number
  complaintCaseId: number
  action: string
  fromStatus: string | null
  toStatus: string
  actorUserId: number
  reasonMasked: string
  createdAt: string
}

export interface ComplaintHitDetail {
  complaintHit: ComplaintHit
  history: ComplaintHitHistory[]
}

export interface ComplaintCaseDetail {
  complaintCase: ComplaintCase
  history: ComplaintCaseHistory[]
  empathyScript: string | null
  empathyScripts?: ComplaintEmpathyScript[]
}

export interface ComplaintDetectionResult {
  sourceType: string
  sourceId: string
  hits: ComplaintHit[]
}

export interface ComplaintListQuery {
  page: number
  size: number
  status?: string
  routingStatus?: string
  keyword?: string
}

export interface ComplaintReasonRequest {
  version: number
  reason: string
}

export interface UpdateComplaintPolicyRequest extends ComplaintReasonRequest {
  enabled: boolean
  fallbackRecipientUserId: number | null
  empathyScript: string
}

export interface CreateComplaintKeywordRequest {
  keyword: string
}

export interface UpdateComplaintKeywordRequest extends ComplaintReasonRequest {
  keyword: string
}

function idempotency(key: string) {
  return { headers: { 'Idempotency-Key': key } }
}

export async function getComplaintPolicy(): Promise<ComplaintPolicy> {
  const res = await http.get<ApiResponse<ComplaintPolicy>>('/complaint-automation/settings')
  return res.data.data
}

export async function updateComplaintPolicy(body: UpdateComplaintPolicyRequest, key: string): Promise<ComplaintPolicy> {
  const res = await http.patch<ApiResponse<ComplaintPolicy>>('/complaint-automation/settings', body, idempotency(key))
  return res.data.data
}

export async function listComplaintKeywords(): Promise<ComplaintKeyword[]> {
  const res = await http.get<ApiResponse<ComplaintKeyword[]>>('/complaint-automation/keywords')
  return res.data.data
}

export async function createComplaintKeyword(body: CreateComplaintKeywordRequest, key: string): Promise<ComplaintKeyword> {
  const res = await http.post<ApiResponse<ComplaintKeyword>>('/complaint-automation/keywords', body, idempotency(key))
  return res.data.data
}

export async function updateComplaintKeyword(id: number, body: UpdateComplaintKeywordRequest, key: string): Promise<ComplaintKeyword> {
  const res = await http.patch<ApiResponse<ComplaintKeyword>>(`/complaint-automation/keywords/${id}`, body, idempotency(key))
  return res.data.data
}

export async function disableComplaintKeyword(id: number, body: ComplaintReasonRequest, key: string): Promise<ComplaintKeyword> {
  const res = await http.post<ApiResponse<ComplaintKeyword>>(`/complaint-automation/keywords/${id}/disable`, body, idempotency(key))
  return res.data.data
}

export async function detectFollowupComplaint(id: number, version: number, key: string): Promise<ComplaintDetectionResult> {
  const res = await http.post<ApiResponse<ComplaintDetectionResult>>(`/complaint-detection/followups/${id}`, { version }, idempotency(key))
  return res.data.data
}

export async function detectWecomComplaint(id: number, version: number, key: string): Promise<ComplaintDetectionResult> {
  const res = await http.post<ApiResponse<ComplaintDetectionResult>>(`/complaint-detection/wecom-messages/${id}`, { version }, idempotency(key))
  return res.data.data
}

export async function listComplaintHits(query: ComplaintListQuery): Promise<PageResult<ComplaintHit>> {
  const res = await http.get<ApiResponse<PageResult<ComplaintHit>>>('/complaint-hits', {
    params: {
      page: query.page,
      size: query.size,
      status: query.status || undefined,
      routingStatus: query.routingStatus || undefined,
      keyword: query.keyword || undefined,
    },
  })
  return res.data.data
}

export async function getComplaintHit(id: number): Promise<ComplaintHitDetail> {
  const res = await http.get<ApiResponse<ComplaintHitDetail>>(`/complaint-hits/${id}`)
  return res.data.data
}

export async function confirmComplaintHit(id: number, body: ComplaintReasonRequest, key: string): Promise<ComplaintCaseDetail> {
  const res = await http.post<ApiResponse<ComplaintCaseDetail>>(`/complaint-hits/${id}/confirm`, body, idempotency(key))
  return res.data.data
}

export async function dismissComplaintHit(id: number, body: ComplaintReasonRequest, key: string): Promise<ComplaintHitDetail> {
  const res = await http.post<ApiResponse<ComplaintHitDetail>>(`/complaint-hits/${id}/dismiss`, body, idempotency(key))
  return res.data.data
}

export async function listComplaints(query: ComplaintListQuery): Promise<PageResult<ComplaintCase>> {
  const res = await http.get<ApiResponse<PageResult<ComplaintCase>>>('/complaints', {
    params: {
      page: query.page,
      size: query.size,
      status: query.status || undefined,
      keyword: query.keyword || undefined,
    },
  })
  return res.data.data
}

export async function getComplaint(id: number): Promise<ComplaintCaseDetail> {
  const res = await http.get<ApiResponse<ComplaintCaseDetail>>(`/complaints/${id}`)
  return res.data.data
}

export async function assignComplaint(id: number, body: ComplaintReasonRequest, key: string): Promise<ComplaintCaseDetail> {
  const res = await http.post<ApiResponse<ComplaintCaseDetail>>(`/complaints/${id}/assign`, body, idempotency(key))
  return res.data.data
}

export async function resolveComplaint(id: number, body: { version: number; resolution: string }, key: string): Promise<ComplaintCaseDetail> {
  const res = await http.post<ApiResponse<ComplaintCaseDetail>>(`/complaints/${id}/resolve`, body, idempotency(key))
  return res.data.data
}

export async function closeComplaint(id: number, body: ComplaintReasonRequest, key: string): Promise<ComplaintCaseDetail> {
  const res = await http.post<ApiResponse<ComplaintCaseDetail>>(`/complaints/${id}/close`, body, idempotency(key))
  return res.data.data
}

export async function dismissComplaint(id: number, body: ComplaintReasonRequest, key: string): Promise<ComplaintCaseDetail> {
  const res = await http.post<ApiResponse<ComplaintCaseDetail>>(`/complaints/${id}/dismiss`, body, idempotency(key))
  return res.data.data
}

export async function listComplaintEmpathyScripts(): Promise<ComplaintEmpathyScript[]> {
  const res = await http.get<ApiResponse<ComplaintEmpathyScript[]>>('/complaint-automation/empathy-scripts')
  return res.data.data
}

export async function createComplaintEmpathyScript(body: CreateComplaintEmpathyScriptRequest, key: string): Promise<ComplaintEmpathyScript> {
  const res = await http.post<ApiResponse<ComplaintEmpathyScript>>('/complaint-automation/empathy-scripts', body, idempotency(key))
  return res.data.data
}

export async function updateComplaintEmpathyScript(id: number, body: UpdateComplaintEmpathyScriptRequest, key: string): Promise<ComplaintEmpathyScript> {
  const res = await http.patch<ApiResponse<ComplaintEmpathyScript>>(`/complaint-automation/empathy-scripts/${id}`, body, idempotency(key))
  return res.data.data
}

export async function disableComplaintEmpathyScript(id: number, body: ComplaintReasonRequest, key: string): Promise<ComplaintEmpathyScript> {
  const res = await http.post<ApiResponse<ComplaintEmpathyScript>>(`/complaint-automation/empathy-scripts/${id}/disable`, body, idempotency(key))
  return res.data.data
}
