import type { ApiResponse, PageResult } from '../types/api'
import { http } from './http'

export type RefundStatus = 'REQUESTED' | 'APPROVED' | 'REJECTED' | 'PROCESSING' | 'SUCCEEDED' | 'FAILED'

export interface Refund {
  id: number
  tenantId: number
  refundNo: string
  orderId: number
  customerId: number
  requestedFen: number
  approvedFen: number
  succeededFen: number
  reasonCode: string
  reasonDetail: string | null
  status: RefundStatus
  requestedBy: number
  approvedBy: number | null
  executedBy: number | null
  succeededAt: string | null
  createdAt: string
  updatedAt: string
  version: number
}

export interface RefundItem {
  id: number
  tenantId: number
  refundId: number
  entitlementId: number
  revokeCount: number
  allocatedFen: number
}

export interface RefundPayment {
  id: number
  tenantId: number
  refundId: number
  provider: string
  externalRefundNo: string
  amountFen: number
  status: 'PENDING' | 'SUCCEEDED' | 'FAILED'
  verificationMethod: 'MANUAL_REVIEW' | 'PROVIDER_CALLBACK'
  evidenceRef: string | null
  voucherFileId: number | null
  succeededAt: string | null
  createdAt: string
  updatedAt: string
  version: number
}

export interface RefundStatusHistory {
  id: number
  tenantId: number
  refundId: number
  fromStatus: string
  toStatus: RefundStatus
  actorUserId: number | null
  reason: string | null
  createdAt: string
}

export interface RefundDetail {
  refund: Refund
  items: RefundItem[]
  payments: RefundPayment[]
  history: RefundStatusHistory[]
}

export interface CreateRefundInput {
  orderId: number
  requestedFen: number
  reasonCode: string
  reasonDetail?: string
  items: { entitlementId: number; revokeCount: number; allocatedFen: number }[]
}

export interface ReviewRefundInput {
  version: number
  decision: 'APPROVED' | 'REJECTED'
  approvedFen?: number
  reason: string
}

export interface ExecuteRefundInput {
  version: number
  provider: 'BANK_TRANSFER' | 'OFFLINE' | 'MOCK'
  externalRefundNo: string
  amountFen: number
  evidenceRef?: string
}

export interface ConfirmRefundSuccessInput {
  externalRefundNo: string
  amountFen: number
  succeededAt: string
  evidenceRef: string
  voucherFileId: number
}

export async function listRefunds(query: {
  page: number
  size: number
  orderId?: number
  customerId?: number
  status?: string
}): Promise<PageResult<Refund>> {
  const res = await http.get<ApiResponse<PageResult<Refund>>>('/refunds', { params: query })
  return res.data.data
}

export async function getRefund(id: number): Promise<RefundDetail> {
  const res = await http.get<ApiResponse<RefundDetail>>(`/refunds/${id}`)
  return res.data.data
}

async function postWithKey<T>(url: string, body: unknown, idempotencyKey: string): Promise<T> {
  const res = await http.post<ApiResponse<T>>(url, body, { headers: { 'Idempotency-Key': idempotencyKey } })
  return res.data.data
}

export function createRefund(body: CreateRefundInput, key: string): Promise<RefundDetail> {
  return postWithKey('/refunds', body, key)
}

export function reviewRefund(id: number, body: ReviewRefundInput, key: string): Promise<RefundDetail> {
  return postWithKey(`/refunds/${id}/review`, body, key)
}

export function executeRefund(id: number, body: ExecuteRefundInput, key: string): Promise<RefundDetail> {
  return postWithKey(`/refunds/${id}/executions`, body, key)
}

export function confirmRefundSuccess(id: number, body: ConfirmRefundSuccessInput, key: string): Promise<RefundDetail> {
  return postWithKey(`/refunds/${id}/success`, body, key)
}
