import type { ApiResponse, PageResult } from '../types/api'
import { http } from './http'

export interface QuickReplyCategory {
  id: number
  parentId: number | null
  name: string
  code: string
  scopeType: 'GLOBAL' | 'INSTITUTION' | 'DEPARTMENT' | 'PERSONAL'
  scopeId: number | null
  sortNo: number
  status: 'ACTIVE' | 'DISABLED'
  createdBy: number | null
  createdAt: string
  updatedAt: string
  version: number
}

export interface QuickReplyTemplate {
  id: number
  categoryId: number
  name: string
  code: string
  messageType: 'TEXT'
  content: string
  scene: string
  scopeType: QuickReplyCategory['scopeType']
  scopeId: number | null
  allowEdit: boolean
  requirePreview: boolean
  aiEnabled: boolean
  featured: boolean
  favorite: boolean
  lastUsedAt: string | null
  usageCount: number
  status: 'ACTIVE' | 'DISABLED'
  sortNo: number
  createdBy: number | null
  createdAt: string
  updatedAt: string
  version: number
}

export interface QuickReplyVariable {
  variableCode: string
  variableName: string
  dataSource: string
  dataType: string
  required: boolean
  description: string
}

export interface QuickReplyPreview {
  templateId: number
  content: string
  variables: Record<string, unknown>
  missingVariables: string[]
  canSend: boolean
  allowEdit: boolean
}

export interface QuickReplyUsageInput {
  customerId: number
  externalUserid: string
  scene: string
  originalContent: string
  finalContent: string
  edited: boolean
  sendStatus: 'COPIED' | 'SDK_SUCCESS' | 'SDK_FAILED' | 'SDK_UNKNOWN'
  clientResult?: string
  aiSuggestionId?: number
}

export interface QuickReplyUsage {
  id: number
  templateId: number
  employeeId: number
  customerId: number
  externalUserid: string
  scene: string
  originalContent: string
  finalContent: string
  edited: boolean
  sendStatus: QuickReplyUsageInput['sendStatus']
  clientResult: string | null
  aiSuggestionId: number | null
  sendTime: string
  createdAt: string
}

export interface QuickReplyTemplateInput {
  categoryId: number
  name: string
  code: string
  messageType: 'TEXT'
  content: string
  scene: string
  scopeType: QuickReplyCategory['scopeType']
  scopeId: number | null
  allowEdit: boolean
  requirePreview: boolean
  aiEnabled: boolean
  featured: boolean
  sortNo: number
  version?: number
  reason: string
}

export interface QuickReplyCategoryInput {
  name: string
  code: string
  parentId: number | null
  scopeType: QuickReplyCategory['scopeType']
  scopeId: number | null
  sortNo: number
  version?: number
  reason: string
}

export async function createQuickReply(body: QuickReplyTemplateInput, key: string): Promise<QuickReplyTemplate> {
  const res = await http.post<ApiResponse<QuickReplyTemplate>>('/quick-replies', body, idempotency(key))
  return res.data.data
}

export async function updateQuickReply(id: number, body: QuickReplyTemplateInput, key: string): Promise<QuickReplyTemplate> {
  const res = await http.patch<ApiResponse<QuickReplyTemplate>>(`/quick-replies/${id}`, body, idempotency(key))
  return res.data.data
}

export async function disableQuickReply(id: number, body: { version: number; reason: string }, key: string): Promise<QuickReplyTemplate> {
  const res = await http.post<ApiResponse<QuickReplyTemplate>>(`/quick-replies/${id}/disable`, body, idempotency(key))
  return res.data.data
}

export async function createQuickReplyCategory(body: QuickReplyCategoryInput, key: string): Promise<QuickReplyCategory> {
  const res = await http.post<ApiResponse<QuickReplyCategory>>('/quick-replies/categories', body, idempotency(key))
  return res.data.data
}

export async function updateQuickReplyCategory(id: number, body: QuickReplyCategoryInput, key: string): Promise<QuickReplyCategory> {
  const res = await http.patch<ApiResponse<QuickReplyCategory>>(`/quick-replies/categories/${id}`, body, idempotency(key))
  return res.data.data
}

export async function disableQuickReplyCategory(id: number, body: { version: number; reason: string }, key: string): Promise<QuickReplyCategory> {
  const res = await http.post<ApiResponse<QuickReplyCategory>>(`/quick-replies/categories/${id}/disable`, body, idempotency(key))
  return res.data.data
}

function idempotency(key: string) {
  return { headers: { 'Idempotency-Key': key } }
}

export async function listQuickReplyCategories(): Promise<QuickReplyCategory[]> {
  const res = await http.get<ApiResponse<QuickReplyCategory[]>>('/quick-replies/categories')
  return res.data.data
}

export async function listQuickReplyVariables(): Promise<QuickReplyVariable[]> {
  const res = await http.get<ApiResponse<QuickReplyVariable[]>>('/quick-replies/variables')
  return res.data.data
}

export async function listQuickReplies(scene?: string): Promise<QuickReplyTemplate[]> {
  const res = await http.get<ApiResponse<QuickReplyTemplate[]>>('/quick-replies', {
    params: scene ? { scene } : {},
  })
  return res.data.data
}

export async function previewQuickReply(id: number, customerId: number, scene?: string): Promise<QuickReplyPreview> {
  const res = await http.post<ApiResponse<QuickReplyPreview>>(`/quick-replies/${id}/preview`, {
    customerId, scene: scene || undefined,
  })
  return res.data.data
}

export async function recordQuickReplyUsage(id: number, body: QuickReplyUsageInput, key: string): Promise<QuickReplyUsage> {
  const res = await http.post<ApiResponse<QuickReplyUsage>>(`/quick-replies/${id}/usage`, body, idempotency(key))
  return res.data.data
}

export async function listQuickReplyUsages(query: { page: number; size: number; templateId?: number; employeeId?: number }): Promise<PageResult<QuickReplyUsage>> {
  const res = await http.get<ApiResponse<PageResult<QuickReplyUsage>>>('/quick-replies/usages', { params: query })
  return res.data.data
}

export interface QuickReplyUsageStat {
  templateId: number
  templateName: string
  templateCode: string
  totalCount: number
  copiedCount: number
  successCount: number
  failedCount: number
  unknownCount: number
  editedCount: number
  employeeCount: number
  successRate: number | null
  lastUsedAt: string | null
}

export async function setQuickReplyFavorite(id: number, favorite: boolean, key: string): Promise<QuickReplyTemplate> {
  const method = favorite ? 'put' : 'delete'
  const res = await http.request<ApiResponse<QuickReplyTemplate>>({
    method, url: `/quick-replies/${id}/favorite`, ...idempotency(key),
  })
  return res.data.data
}

export async function getQuickReplyStatistics(query: { employeeId?: number; from?: string; to?: string } = {}): Promise<QuickReplyUsageStat[]> {
  const res = await http.get<ApiResponse<QuickReplyUsageStat[]>>('/quick-replies/statistics', { params: query })
  return res.data.data
}
