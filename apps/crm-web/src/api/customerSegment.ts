import type { ApiResponse, PageResult } from '../types/api'
import type { CustomerListItem } from './customer'
import { http } from './http'

export interface CustomerSegmentFilters {
  cityCodes: string[]
  tierCodes: string[]
  attentionProjectIds: number[]
  purchasedProjectIds: number[]
  redeemedProjectIds: number[]
  redeemedMinCount: number
}

export interface CustomerSegment {
  id: number
  name: string
  filters: CustomerSegmentFilters
  version: number
  createdAt: string
  updatedAt: string
}

export interface CustomerSegmentInput {
  name: string
  filters: CustomerSegmentFilters
}

export interface CustomerSegmentBatch {
  id: number
  segmentId: number
  batchNo: string
  operationType: string
  memberCount: number
  createdAt: string
}

export async function listCustomerSegments(query: { page: number; size: number; keyword?: string }) {
  const res = await http.get<ApiResponse<PageResult<CustomerSegment>>>('/customer-segments', {
    params: { page: query.page, size: query.size, keyword: query.keyword || undefined },
  })
  return res.data.data
}

export async function getCustomerSegment(id: number): Promise<CustomerSegment> {
  const res = await http.get<ApiResponse<CustomerSegment>>(`/customer-segments/${id}`)
  return res.data.data
}

export async function createCustomerSegment(input: CustomerSegmentInput, idempotencyKey: string) {
  const res = await http.post<ApiResponse<CustomerSegment>>('/customer-segments', input, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
  return res.data.data
}

export async function updateCustomerSegment(id: number, input: CustomerSegmentInput & { version: number }) {
  const res = await http.patch<ApiResponse<CustomerSegment>>(`/customer-segments/${id}`, input)
  return res.data.data
}

export async function deleteCustomerSegment(id: number, version: number): Promise<void> {
  await http.delete<ApiResponse<null>>(`/customer-segments/${id}`, { params: { version } })
}

export async function previewCustomerSegment(input: {
  filters: CustomerSegmentFilters
  page: number
  size: number
}): Promise<PageResult<CustomerListItem>> {
  const res = await http.post<ApiResponse<PageResult<CustomerListItem>>>('/customer-segments/preview', input)
  return res.data.data
}

export async function listCustomerSegmentMembers(id: number, page: number, size: number) {
  const res = await http.get<ApiResponse<PageResult<CustomerListItem>>>(`/customer-segments/${id}/members`, {
    params: { page, size },
  })
  return res.data.data
}

export async function createSegmentOperationBatch(
  id: number,
  input: { operationType: 'FOLLOWUP'; version: number },
  idempotencyKey: string,
): Promise<CustomerSegmentBatch> {
  const res = await http.post<ApiResponse<CustomerSegmentBatch>>(
    `/customer-segments/${id}/operation-batches`, input,
    { headers: { 'Idempotency-Key': idempotencyKey } },
  )
  return res.data.data
}
