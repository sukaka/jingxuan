import type { ApiResponse, PageResult } from '../types/api'
import { http } from './http'

export interface RiskCase {
  id: number
  riskCaseNo: string
  riskType: string
  severity: string
  sourceType: string
  sourceRef: string
  status: string
  ownerUserId: number | null
  evidence: string | null
  resolution: string | null
  resolvedAt: string | null
  createdAt: string
  updatedAt: string
  version: number
  orderId: number | null
  orderNo: string | null
  customerId: number | null
  customerNo: string | null
  customerName: string | null
}

export interface RiskCaseHistory {
  id: number
  riskCaseId: number
  action: string
  fromStatus: string | null
  toStatus: string
  actorUserId: number | null
  note: string | null
  createdAt: string
}

export interface RiskCaseDetail {
  riskCase: RiskCase
  history: RiskCaseHistory[]
}

export interface RiskCaseListQuery {
  page: number
  size: number
  keyword?: string
  status?: string
  severity?: string
  riskType?: string
}

export interface RiskReasonRequest {
  version: number
  reason: string
}

export interface RiskResolveRequest {
  version: number
  resolution: string
}

export async function listRiskCases(query: RiskCaseListQuery): Promise<PageResult<RiskCase>> {
  const res = await http.get<ApiResponse<PageResult<RiskCase>>>('/risks', {
    params: {
      page: query.page,
      size: query.size,
      keyword: query.keyword || undefined,
      status: query.status || undefined,
      severity: query.severity || undefined,
      riskType: query.riskType || undefined,
    },
  })
  return res.data.data
}

export async function getRiskCase(id: number): Promise<RiskCaseDetail> {
  const res = await http.get<ApiResponse<RiskCaseDetail>>(`/risks/${id}`)
  return res.data.data
}

export async function claimRiskCase(id: number, body: RiskReasonRequest, idempotencyKey: string): Promise<RiskCaseDetail> {
  const res = await http.post<ApiResponse<RiskCaseDetail>>(`/risks/${id}/claim`, body, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
  return res.data.data
}

export async function resolveRiskCase(id: number, body: RiskResolveRequest, idempotencyKey: string): Promise<RiskCaseDetail> {
  const res = await http.post<ApiResponse<RiskCaseDetail>>(`/risks/${id}/resolve`, body, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
  return res.data.data
}

export async function closeRiskCase(id: number, body: RiskReasonRequest, idempotencyKey: string): Promise<RiskCaseDetail> {
  const res = await http.post<ApiResponse<RiskCaseDetail>>(`/risks/${id}/close`, body, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
  return res.data.data
}
