import type { ApiResponse, PageResult } from '../types/api'
import { http } from './http'

export type KnowledgeProvider = 'disabled' | 'mock' | 'dify'
export type KnowledgeDocumentStatus = 'WAITING' | 'SYNCING' | 'ACTIVE' | 'FAILED' | 'DISABLED'
export type KnowledgeSyncStatus = 'WAITING' | 'PENDING' | 'RUNNING' | 'INDEXING' | 'RETRY' | 'SUCCEEDED' | 'FAILED'

export interface DifyKnowledgeConfig {
  provider: KnowledgeProvider
  baseUrl: string | null
  apiKeyConfigured: boolean
  datasetId: string | null
  datasetApiKeyConfigured: boolean
  syncEnabled: boolean
  timeoutSeconds: number
  enabled: boolean
  connectionStatus: 'UNVERIFIED' | 'CONNECTED' | 'FAILED'
  lastErrorMasked: string | null
  lastConnectedAt: string | null
  version: number
}

export interface DifyKnowledgeConfigInput {
  provider: KnowledgeProvider
  baseUrl: string
  apiKey?: string
  datasetId?: string
  datasetApiKey?: string
  syncEnabled?: boolean
  timeoutSeconds: number
  enabled: boolean
  version: number
}

export async function getKnowledgeConfig(): Promise<DifyKnowledgeConfig> {
  const res = await http.get<ApiResponse<DifyKnowledgeConfig>>('/ai/knowledge/config')
  return res.data.data
}

export async function saveKnowledgeConfig(body: DifyKnowledgeConfigInput, key: string): Promise<DifyKnowledgeConfig> {
  const res = await http.put<ApiResponse<DifyKnowledgeConfig>>('/ai/knowledge/config', body, {
    headers: { 'Idempotency-Key': key },
  })
  return res.data.data
}

export async function testKnowledgeConfig(key: string): Promise<DifyKnowledgeConfig> {
  const res = await http.post<ApiResponse<DifyKnowledgeConfig>>('/ai/knowledge/config/test', undefined, {
    headers: { 'Idempotency-Key': key },
  })
  return res.data.data
}

export interface KnowledgeDocumentItem {
  id: number
  materialId: number
  activeRevisionId: number | null
  datasetId: string | null
  difyDocumentId: string | null
  status: KnowledgeDocumentStatus
  publishedAt: string | null
  latestJobId: number | null
  latestJobStatus: KnowledgeSyncStatus | null
  attemptCount: number | null
  lastErrorMasked: string | null
  version: number
}

export interface KnowledgeSyncJob {
  id: number
  documentId: number
  revisionId: number
  status: KnowledgeSyncStatus
  contentHash: string
  attemptCount: number
  lastErrorMasked: string | null
  version: number
}

export interface PublishKnowledgeDocumentInput {
  materialId: number
  revisionId: number
  version: number
}

export async function listKnowledgeDocuments(query: { page: number; size: number; status?: KnowledgeDocumentStatus }): Promise<PageResult<KnowledgeDocumentItem>> {
  const res = await http.get<ApiResponse<PageResult<KnowledgeDocumentItem>>>('/ai/knowledge/documents', { params: query })
  return res.data.data
}

export async function publishKnowledgeDocument(body: PublishKnowledgeDocumentInput, key: string): Promise<KnowledgeSyncJob> {
  const res = await http.post<ApiResponse<KnowledgeSyncJob>>('/ai/knowledge/documents/publications', body, {
    headers: { 'Idempotency-Key': key },
  })
  return res.data.data
}

export async function retryKnowledgeSyncJob(id: number, key: string): Promise<KnowledgeSyncJob> {
  const res = await http.post<ApiResponse<KnowledgeSyncJob>>(`/ai/knowledge/sync-jobs/${id}/retry`, undefined, {
    headers: { 'Idempotency-Key': key },
  })
  return res.data.data
}

export async function disableKnowledgeDocument(id: number, key: string): Promise<KnowledgeDocumentItem> {
  const res = await http.post<ApiResponse<KnowledgeDocumentItem>>(`/ai/knowledge/documents/${id}/disable`, undefined, {
    headers: { 'Idempotency-Key': key },
  })
  return res.data.data
}
