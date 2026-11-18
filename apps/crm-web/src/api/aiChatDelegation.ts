import type { ApiResponse, PageResult } from '../types/api'
import { http } from './http'

export type DelegationScopeType = 'TEAM' | 'STAFF'
export type DelegationCustomerMode = 'ACTIVE' | 'PAUSED' | 'HUMAN_TAKEOVER'

export interface AiDelegationScope {
  scopeType: DelegationScopeType
  scopeId: number
  enabled: boolean
}

export interface AiDelegationPolicy {
  tenantId?: number
  enabled: boolean
  emergencyStopped: boolean
  minIntervalSeconds: number
  dailyLimit: number
  allowedStart: string
  allowedEnd: string
  zoneId: string
  blockedTerms: string[]
  scopes: AiDelegationScope[]
  version: number
}

export interface UpdateAiDelegationPolicyRequest {
  enabled: boolean
  minIntervalSeconds: number
  dailyLimit: number
  allowedStart: string
  allowedEnd: string
  zoneId: string
  blockedTerms: string[]
  scopes: AiDelegationScope[]
  version: number
  reason: string
}

export interface AiDelegationStatus {
  enabled: boolean
  emergencyStopped: boolean
  workerEnabled: boolean
  eventConsumerEnabled: boolean
  senderMode: 'OFFICIAL' | 'MOCK' | 'DISABLED' | string
  realOfficialEnabled: boolean
  counts: Record<string, number>
}

export interface AiDelegationRun {
  id: number
  sourceMessageId: number
  customerId: number | null
  staffUserId: number
  teamId: number | null
  status: string
  reasonMasked: string | null
  intent: string | null
  aiProvider: string | null
  aiModel?: string | null
  sendProvider: string | null
  providerMessageId: string | null
  sendAttemptedAt?: string | null
  generationAttempts: number
  sendAttempts: number
  nextAttemptAt: string | null
  createdAt: string
  updatedAt: string
  version: number
}

export interface AiDelegationCustomerControl {
  customerId: number
  mode: DelegationCustomerMode
  reason: string | null
  updatedAt?: string | null
  version: number
}

export async function getAiDelegationPolicy(): Promise<AiDelegationPolicy> {
  const res = await http.get<ApiResponse<AiDelegationPolicy>>('/ai/chat-delegation/policy')
  return res.data.data
}

export async function updateAiDelegationPolicy(
  body: UpdateAiDelegationPolicyRequest, idempotencyKey: string = newKey(),
): Promise<AiDelegationPolicy> {
  const res = await http.put<ApiResponse<AiDelegationPolicy>>('/ai/chat-delegation/policy', body, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
  return res.data.data
}

export async function setAiDelegationEmergencyStop(
  body: { stopped: boolean; version: number; reason: string }, idempotencyKey: string = newKey(),
): Promise<AiDelegationPolicy> {
  const res = await http.post<ApiResponse<AiDelegationPolicy>>('/ai/chat-delegation/emergency-stop', body, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
  return res.data.data
}

export async function getAiDelegationStatus(): Promise<AiDelegationStatus> {
  const res = await http.get<ApiResponse<AiDelegationStatus>>('/ai/chat-delegation/status')
  return res.data.data
}

export async function getAiDelegationRuns(query: {
  customerId?: number
  status?: string
  page: number
  size: number
}): Promise<PageResult<AiDelegationRun>> {
  const res = await http.get<ApiResponse<PageResult<AiDelegationRun>>>('/ai/chat-delegation/runs', { params: query })
  return res.data.data
}

export async function getAiDelegationCustomerControl(customerId: number): Promise<AiDelegationCustomerControl> {
  const res = await http.get<ApiResponse<AiDelegationCustomerControl>>(
    `/ai/chat-delegation/customers/${customerId}/control`,
  )
  return res.data.data
}

export async function setAiDelegationCustomerControl(
  customerId: number,
  body: { mode: DelegationCustomerMode; version: number; reason: string },
  idempotencyKey: string = newKey(),
): Promise<AiDelegationCustomerControl> {
  const res = await http.put<ApiResponse<AiDelegationCustomerControl>>(
    `/ai/chat-delegation/customers/${customerId}/control`, body,
    { headers: { 'Idempotency-Key': idempotencyKey } },
  )
  return res.data.data
}

function newKey(): string {
  return typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `ai-delegation-${Date.now()}-${Math.random()}`
}
