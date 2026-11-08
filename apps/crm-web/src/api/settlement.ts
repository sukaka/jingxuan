import type { ApiResponse, PageResult } from '../types/api'
import { http } from './http'

export type SettlementStatus = 'DRAFT' | 'CONFIRMED' | 'PARTIALLY_PAID' | 'PAID'
export type AmountStatus = 'PENDING_VERIFICATION' | 'VERIFIED'

export interface SettlementBatch {
  id: number
  institutionId: number
  settlementType: 'MONTHLY' | 'PER_REDEMPTION'
  periodKey: string
  status: SettlementStatus
  amountStatus: AmountStatus
  grossFen: number
  commissionFen: number
  netPayableFen: number
  paidFen: number
  ruleVersion: string | null
  verificationEvidence: string | null
  createdAt: string
  updatedAt: string
  version: number
}

export interface SettlementLine {
  id: number
  settlementBatchId: number
  redemptionId: number
  amountStatus: AmountStatus
  grossFen: number
  commissionFen: number
  payableFen: number
  rateSnapshot: string
  pendingReason: string | null
  createdAt: string
}

export interface SettlementPayment {
  id: number
  settlementBatchId: number
  amountFen: number
  bankTradeNo: string
  paidAt: string
  voucherFileId: number
  evidenceRef: string
  createdBy: number
  createdAt: string
}

export interface SettlementBatchDetail {
  batch: SettlementBatch
  lines: SettlementLine[]
  payments: SettlementPayment[]
}

export interface ReconciliationIssue {
  checkType: 'ENTITLEMENT_LEDGER' | 'ORDER_PAYMENT_CASH' | 'ORDER_REFUND_CASH' | 'REFUND_ALLOCATION' | 'SETTLEMENT_PAYMENT' | 'UNBATCHED_REDEMPTION'
  resourceId: number
  institutionId: number | null
  expectedCount: number | null
  actualCount: number | null
  expectedFen: number | null
  actualFen: number | null
  detail: string
}

export interface ReconciliationResponse {
  generatedAt: string
  issueCount: number
  issues: ReconciliationIssue[]
}

export interface CreateSettlementBatchInput {
  institutionId: number
  settlementType: 'MONTHLY' | 'PER_REDEMPTION'
  periodKey: string
  redemptionIds: number[]
}

export interface VerifySettlementBatchInput {
  version: number
  ruleVersion: string
  grossFen: number
  commissionFen: number
  netPayableFen: number
  verificationEvidence: string
}

export interface RecordSettlementPaymentInput {
  version: number
  amountFen: number
  bankTradeNo: string
  paidAt: string
  voucherFileId: number
  evidenceRef: string
}

export async function listSettlementBatches(query: {
  page: number
  size: number
  institutionId?: number
  status?: string
}): Promise<PageResult<SettlementBatch>> {
  const res = await http.get<ApiResponse<PageResult<SettlementBatch>>>('/settlements/batches', { params: query })
  return res.data.data
}

export async function getSettlementBatch(id: number): Promise<SettlementBatchDetail> {
  const res = await http.get<ApiResponse<SettlementBatchDetail>>(`/settlements/batches/${id}`)
  return res.data.data
}

export async function getSettlementReconciliation(query: { institutionId?: number } = {}): Promise<ReconciliationResponse> {
  const res = await http.get<ApiResponse<ReconciliationResponse>>('/settlements/reconciliation', { params: query })
  return res.data.data
}

async function postWithKey<T>(url: string, body: unknown, idempotencyKey: string): Promise<T> {
  const res = await http.post<ApiResponse<T>>(url, body, { headers: { 'Idempotency-Key': idempotencyKey } })
  return res.data.data
}

export function createSettlementBatch(body: CreateSettlementBatchInput, key: string): Promise<SettlementBatchDetail> {
  return postWithKey('/settlements/batches', body, key)
}

export function verifySettlementBatch(id: number, body: VerifySettlementBatchInput, key: string): Promise<SettlementBatchDetail> {
  return postWithKey(`/settlements/batches/${id}/verification`, body, key)
}

export function recordSettlementPayment(id: number, body: RecordSettlementPaymentInput, key: string): Promise<SettlementBatchDetail> {
  return postWithKey(`/settlements/batches/${id}/payments`, body, key)
}
