import type { ApiResponse } from '../types/api'
import { http } from './http'

export interface AiReplySuggestion {
  id: number
  templateId: number
  customerId: number
  scene: string
  reply: string
  reason: string
  usedFactCodes: string[]
  provider: string
  model: string
  promptVersion: string
  confidence: number
  status: 'PROPOSED' | 'ACCEPTED' | 'REJECTED'
  accepted: boolean
  edited: boolean
  finalContent: string | null
  createdAt: string
  decidedAt: string | null
  version: number
}

export async function generateAiReply(body: { customerId: number; templateId: number; instruction?: string }, key: string): Promise<AiReplySuggestion> {
  const res = await http.post<ApiResponse<AiReplySuggestion>>('/ai/replies/generate', body, {
    headers: { 'Idempotency-Key': key }, timeout: 35000,
  })
  return res.data.data
}

export async function decideAiReply(id: number, body: {
  decision: 'ACCEPT' | 'REJECT'
  version: number
  finalContent?: string
  reason: string
}, key: string): Promise<AiReplySuggestion> {
  const res = await http.post<ApiResponse<AiReplySuggestion>>(`/ai/replies/${id}/decision`, body, {
    headers: { 'Idempotency-Key': key },
  })
  return res.data.data
}

export async function listAiReplyHistory(customerId: number, size = 20): Promise<AiReplySuggestion[]> {
  const res = await http.get<ApiResponse<AiReplySuggestion[]>>('/ai/replies', { params: { customerId, size } })
  return res.data.data
}
