import type { ApiResponse, PageResult } from '../types/api'
import type { DownloadResponse } from './file'
import { http } from './http'

export type ExportType = 'CUSTOMER' | 'ORDER' | 'REDEMPTION' | 'FOLLOWUP'

export interface ExportFilterInput {
  customerId?: number
  status?: string
  cityCode?: string
  sourceCode?: string
  sourceSystem?: string
  channelCode?: string
  from?: string
  to?: string
}

export interface ExportJob {
  id: number
  jobNo: string
  exportType: ExportType
  status: string
  filters: Record<string, unknown>
  columns: string[]
  scope: Record<string, unknown>
  reason: string
  rowCount: number | null
  fileId: number | null
  expiresAt: string | null
  lastError: string | null
  startedAt: string | null
  finishedAt: string | null
  createdAt: string
  updatedAt: string
  version: number
}

export interface CreateExportJobRequest {
  exportType: ExportType
  filters?: ExportFilterInput
  columns: string[]
  reason: string
}

export interface ExportJobListQuery {
  page: number
  size: number
  exportType?: ExportType | ''
  status?: string
}

export interface ExportAccessLog {
  id: number
  jobId: number | null
  actorUserId: number
  action: 'REQUEST' | 'DOWNLOAD'
  result: 'ALLOWED' | 'DENIED'
  reason: string | null
  denialCode: string | null
  createdAt: string
}

export async function createExportJob(body: CreateExportJobRequest, idempotencyKey: string): Promise<ExportJob> {
  const res = await http.post<ApiResponse<ExportJob>>('/exports', body, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
  return res.data.data
}

export async function listExportJobs(query: ExportJobListQuery): Promise<PageResult<ExportJob>> {
  const res = await http.get<ApiResponse<PageResult<ExportJob>>>('/exports', {
    params: {
      page: query.page,
      size: query.size,
      exportType: query.exportType || undefined,
      status: query.status || undefined,
    },
  })
  return res.data.data
}

export async function getExportJob(id: number): Promise<ExportJob> {
  const res = await http.get<ApiResponse<ExportJob>>(`/exports/${id}`)
  return res.data.data
}

export async function getExportJobDownload(id: number): Promise<DownloadResponse> {
  const res = await http.get<ApiResponse<DownloadResponse>>(`/exports/${id}/download`)
  return res.data.data
}

export async function listExportAccessLogs(query: {
  page: number
  size: number
  jobId?: number
  actorId?: number
  action?: 'REQUEST' | 'DOWNLOAD' | ''
  result?: 'ALLOWED' | 'DENIED' | ''
}): Promise<PageResult<ExportAccessLog>> {
  const res = await http.get<ApiResponse<PageResult<ExportAccessLog>>>('/exports/access-logs', {
    params: {
      page: query.page,
      size: query.size,
      ...(query.jobId === undefined ? {} : { jobId: query.jobId }),
      ...(query.actorId === undefined ? {} : { actorId: query.actorId }),
      ...(query.action ? { action: query.action } : {}),
      ...(query.result ? { result: query.result } : {}),
    },
  })
  return res.data.data
}
