import type { ApiResponse, PageResult } from '../types/api'
import { http } from './http'

export interface AiInsight {
  insightType: string
  projectCodes: string[]
  mentionedInstitution: string | null
  mentionedTime: string | null
  absoluteTime: string | null
  evidenceMessageIds: number[]
  confidence: number
  proposedActions: string[]
  factStatus: 'UNVERIFIED'
  allowFinancialWrite: false
  reasoningSummary: string
  replyDraft: string
  followupDraft: { title: string; description: string; due_at: string | null } | null
  riskFlags: string[]
}

export interface AiJob {
  id: number
  customerId: number
  conversationId: string
  requestedBy: number
  status: 'QUEUED' | 'RUNNING' | 'RETRY' | 'PROPOSED' | 'FAILED' | 'CONFIRMED' | 'REJECTED'
  provider: string | null
  modelName: string | null
  errorMasked: string | null
  taskId: number | null
  version: number
  createdAt: string
  insight: AiInsight | null
}

export interface AiQualitySummary {
  pendingReview: number
  riskSuggested: number
  failed: number
  confirmed: number
  riskNotice: string
}

export interface AiConfirmationInput {
  version: number
  decision: 'CONFIRM' | 'REJECT'
  title?: string
  description?: string
  dueAt?: string
  reason?: string
}

export async function createAiJob(messageIds: number[], key: string): Promise<AiJob> {
  const res = await http.post<ApiResponse<AiJob>>('/ai/jobs', { messageIds }, { headers: { 'Idempotency-Key': key } })
  return res.data.data
}

export async function listAiJobs(query: { conversationId?: string; status?: string; page: number; size: number }): Promise<PageResult<AiJob>> {
  const res = await http.get<ApiResponse<PageResult<AiJob>>>('/ai/jobs', { params: query })
  return res.data.data
}

export async function getAiJob(id: number): Promise<AiJob> {
  const res = await http.get<ApiResponse<AiJob>>(`/ai/jobs/${id}`)
  return res.data.data
}

export async function confirmAiJob(id: number, body: AiConfirmationInput, key: string): Promise<AiJob> {
  const res = await http.post<ApiResponse<AiJob>>(`/ai/jobs/${id}/confirmation`, body, { headers: { 'Idempotency-Key': key } })
  return res.data.data
}

export async function getAiQualitySummary(): Promise<AiQualitySummary> {
  const res = await http.get<ApiResponse<AiQualitySummary>>('/ai/quality/summary')
  return res.data.data
}

export type AiProvider = 'disabled' | 'mock' | 'openai_compatible'

export interface AiProviderConfig {
  provider: AiProvider
  baseUrl: string | null
  apiKeyConfigured: boolean
  model: string | null
  timeoutSeconds: number
  enabled: boolean
  connectionStatus: string
  lastErrorMasked: string | null
  lastConnectedAt: string | null
  version: number
}

export interface AiProviderConfigInput {
  provider: AiProvider
  baseUrl: string
  apiKey?: string
  model: string
  timeoutSeconds: number
  enabled: boolean
  version: number
}

export async function getAiConfig(): Promise<AiProviderConfig> {
  const res = await http.get<ApiResponse<AiProviderConfig>>('/ai/config')
  return res.data.data
}

export async function saveAiConfig(body: AiProviderConfigInput, key: string): Promise<AiProviderConfig> {
  const res = await http.put<ApiResponse<AiProviderConfig>>('/ai/config', body, { headers: { 'Idempotency-Key': key } })
  return res.data.data
}

export async function testAiConfig(key: string): Promise<AiProviderConfig> {
  const res = await http.post<ApiResponse<AiProviderConfig>>('/ai/config/test', undefined, { headers: { 'Idempotency-Key': key } })
  return res.data.data
}
