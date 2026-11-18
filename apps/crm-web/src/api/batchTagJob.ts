import type { ApiResponse, PageResult } from '../types/api'
import type { CustomerSegmentBatch, CustomerSegmentFilters } from './customerSegment'
import { http } from './http'

export type BatchTagOperation = 'ADD' | 'REMOVE'
export type BatchTagSourceType = 'FILTER' | 'SEGMENT' | 'OPERATION_BATCH' | 'ROLLBACK'
export type BatchTagJobStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'PARTIAL_FAILED' | 'FAILED'
export type BatchTagItemStatus = 'PENDING' | 'SUCCEEDED' | 'NO_CHANGE' | 'SKIPPED' | 'FAILED'

export interface BatchTagSourceInput {
  type: Exclude<BatchTagSourceType, 'ROLLBACK'>
  id?: number
  filters?: Partial<CustomerSegmentFilters>
}

export interface BatchTagPreviewInput {
  operation: BatchTagOperation
  tagCodes: string[]
  source: BatchTagSourceInput
  page: number
  size: number
}

export interface BatchTagMember {
  customerId: number
  customerNo: string
  customerName: string | null
  ownerStaffId: number
  teamId: number | null
}

export interface BatchTagPreview {
  previewId: string
  operation: BatchTagOperation
  tagCodes: string[]
  sourceType: BatchTagSourceType
  sourceId: number | null
  items: BatchTagMember[]
  total: number
  page: number
  size: number
}

export interface BatchTagJob {
  id: number
  jobNo: string
  requestedBy: number
  operation: BatchTagOperation
  tagCodes: string[]
  sourceType: BatchTagSourceType
  sourceId: number | null
  sourceSnapshot: Record<string, unknown>
  scopeSnapshot: Record<string, unknown>
  idempotencyKey: string
  rollbackOfJobId: number | null
  status: BatchTagJobStatus
  totalCount: number
  succeededCount: number
  noChangeCount: number
  skippedCount: number
  failedCount: number
  attemptCount: number
  lastErrorMasked: string | null
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
  version: number
}

export interface BatchTagJobItem {
  id: number
  customerId: number
  customerNo: string
  customerName: string | null
  ownerStaffId: number
  teamId: number | null
  tagCodes: string[]
  status: BatchTagItemStatus
  failureCode: string | null
  reason: string | null
  beforeTagCodes: string[] | null
  afterTagCodes: string[] | null
  changedTagCodes: string[] | null
  attemptCount: number
  processedAt: string | null
  version: number
}

export interface BatchTagJobDetail {
  job: BatchTagJob
  items: PageResult<BatchTagJobItem>
}

function idempotency(key: string) {
  return { headers: { 'Idempotency-Key': key } }
}

export async function previewBatchTagJob(input: BatchTagPreviewInput, key: string): Promise<BatchTagPreview> {
  const res = await http.post<ApiResponse<BatchTagPreview>>('/batch-tag-jobs/preview', input, idempotency(key))
  return res.data.data
}

export async function createBatchTagJob(previewId: string, key: string): Promise<BatchTagJobDetail> {
  const res = await http.post<ApiResponse<BatchTagJobDetail>>('/batch-tag-jobs', { previewId }, idempotency(key))
  return res.data.data
}

export async function listBatchTagJobs(query: {
  status?: BatchTagJobStatus
  page: number
  size: number
}): Promise<PageResult<BatchTagJob>> {
  const res = await http.get<ApiResponse<PageResult<BatchTagJob>>>('/batch-tag-jobs', { params: query })
  return res.data.data
}

export async function getBatchTagJob(id: number, itemPage = 1, itemSize = 100): Promise<BatchTagJobDetail> {
  const res = await http.get<ApiResponse<BatchTagJobDetail>>(`/batch-tag-jobs/${id}`, {
    params: { itemPage, itemSize },
  })
  return res.data.data
}

export async function executeBatchTagJob(id: number, version: number, key: string): Promise<BatchTagJobDetail> {
  const res = await http.post<ApiResponse<BatchTagJobDetail>>(
    `/batch-tag-jobs/${id}/execute`, { version }, idempotency(key),
  )
  return res.data.data
}

export async function retryBatchTagJob(id: number, version: number, key: string): Promise<BatchTagJobDetail> {
  const res = await http.post<ApiResponse<BatchTagJobDetail>>(
    `/batch-tag-jobs/${id}/retry`, { version }, idempotency(key),
  )
  return res.data.data
}

export async function rollbackBatchTagJob(id: number, version: number, key: string): Promise<BatchTagJobDetail> {
  const res = await http.post<ApiResponse<BatchTagJobDetail>>(
    `/batch-tag-jobs/${id}/rollback`, { version }, idempotency(key),
  )
  return res.data.data
}

export async function createBatchTagOperationBatch(
  segmentId: number,
  version: number,
  key: string,
): Promise<CustomerSegmentBatch> {
  const res = await http.post<ApiResponse<CustomerSegmentBatch>>(
    `/customer-segments/${segmentId}/operation-batches`,
    { operationType: 'FOLLOWUP', version }, idempotency(key),
  )
  return res.data.data
}
