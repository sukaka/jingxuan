import type { ApiResponse } from '../types/api'
import { http } from './http'

export interface IntentIntervalOverride {
  intentLevel: string
  intervalDays: number
}

export interface FollowupAutomationPolicy {
  tenantId?: number
  defaultIntervalDays: number
  intentOverrides: IntentIntervalOverride[]
  version: number
}

export interface UpdateFollowupAutomationPolicyRequest {
  defaultIntervalDays: number
  intentOverrides: IntentIntervalOverride[]
  version: number
}

export interface MonthlyDispatchCandidate {
  customerId: number
  customerName: string
  ownerStaffId: number
  teamId: number | null
  dueAt: string
  orderIds: number[]
}

export interface MonthlyDispatchPreview {
  orderMonth: string
  dispatchMonth: string
  items: MonthlyDispatchCandidate[]
  total: number
  page: number
  size: number
}

export interface MonthlyDispatchItem extends MonthlyDispatchCandidate {
  id: number
  taskId: number | null
  status: 'PENDING' | 'CREATED' | 'SKIPPED' | 'FAILED' | string
  reason: string | null
  version?: number
}

export interface MonthlyDispatchBatch {
  id: number
  orderMonth: string
  dispatchMonth: string
  status: 'READY' | 'RUNNING' | 'COMPLETED' | 'FAILED' | string
  version: number
  retryCount: number
  nextRetryAt?: string | null
  lastErrorMasked?: string | null
  items: MonthlyDispatchItem[]
}

export async function getFollowupAutomationPolicy(): Promise<FollowupAutomationPolicy> {
  const res = await http.get<ApiResponse<FollowupAutomationPolicy>>('/followup-automation/policy')
  return res.data.data
}

export async function updateFollowupAutomationPolicy(
  request: UpdateFollowupAutomationPolicyRequest,
  idempotencyKey: string = crypto.randomUUID(),
): Promise<FollowupAutomationPolicy> {
  const res = await http.put<ApiResponse<FollowupAutomationPolicy>>('/followup-automation/policy', request, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
  return res.data.data
}

export async function previewMonthlyDispatch(query: {
  orderMonth: string
  page: number
  size: number
}): Promise<MonthlyDispatchPreview> {
  const res = await http.get<ApiResponse<MonthlyDispatchPreview>>('/followup-automation/batches/preview', {
    params: query,
  })
  return res.data.data
}

export async function createMonthlyDispatch(
  request: { orderMonth: string },
  idempotencyKey: string = crypto.randomUUID(),
): Promise<MonthlyDispatchBatch> {
  const res = await http.post<ApiResponse<MonthlyDispatchBatch>>('/followup-automation/batches', request, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
  return res.data.data
}

export async function getMonthlyDispatch(id: number): Promise<MonthlyDispatchBatch> {
  const res = await http.get<ApiResponse<MonthlyDispatchBatch>>(`/followup-automation/batches/${id}`)
  return res.data.data
}

async function dispatchAction(
  id: number,
  action: 'execute' | 'retry',
  version: number,
  idempotencyKey: string,
): Promise<MonthlyDispatchBatch> {
  const res = await http.post<ApiResponse<MonthlyDispatchBatch>>(
    `/followup-automation/batches/${id}/${action}`,
    { version },
    { headers: { 'Idempotency-Key': idempotencyKey } },
  )
  return res.data.data
}

export function executeMonthlyDispatch(
  id: number,
  version: number,
  idempotencyKey: string = crypto.randomUUID(),
): Promise<MonthlyDispatchBatch> {
  return dispatchAction(id, 'execute', version, idempotencyKey)
}

export function retryMonthlyDispatch(
  id: number,
  version: number,
  idempotencyKey: string = crypto.randomUUID(),
): Promise<MonthlyDispatchBatch> {
  return dispatchAction(id, 'retry', version, idempotencyKey)
}
