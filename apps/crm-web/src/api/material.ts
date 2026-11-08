import type { ApiResponse, PageResult } from '../types/api'
import type { DownloadResponse } from './file'
import { http } from './http'

export interface Material {
  id: number
  materialNo: string
  title: string
  categoryCode: string
  institutionId: number | null
  projectId: number | null
  cityCode: string | null
  summary: string | null
  tags: string[]
  currentRevisionId: number | null
  reviewStatus: string
  offlineAt: string | null
  offlineBy: number | null
  offlineReason: string | null
  createdBy: number
  createdAt: string
  updatedAt: string
  version: number
  revisions?: MaterialRevision[]
}

export interface MaterialRevision {
  id: number
  materialId: number
  revisionNo: number
  title: string
  summary: string | null
  categoryCode: string
  institutionId: number | null
  projectId: number | null
  cityCode: string | null
  tags: string[]
  fileId: number
  changeNote: string | null
  status: string
  reviewerId: number | null
  reviewNote: string | null
  reviewedAt: string | null
  createdBy: number
  createdAt: string
  updatedAt: string
  version: number
}

export interface MaterialDetail {
  material: Material
  revisions: MaterialRevision[]
}

export interface MaterialListQuery {
  page: number
  size: number
  keyword?: string
  categoryCode?: string
  status?: string
  institutionId?: number
  projectId?: number
  cityCode?: string
  globalOnly?: boolean
}

export interface MaterialMetadataInput {
  title: string
  categoryCode: string
  institutionId?: number
  projectId?: number
  cityCode?: string
  summary?: string
  tags: string[]
}

export interface CreateMaterialRequest extends MaterialMetadataInput {
  fileId: number
  changeNote?: string
}

export interface UpdateMaterialRequest extends MaterialMetadataInput {
  revisionId: number
  version: number
  fileId: number
  changeNote?: string
}

export interface CreateMaterialRevisionRequest extends MaterialMetadataInput {
  version: number
  fileId: number
  changeNote?: string
}

export interface ReviewMaterialRevisionRequest {
  version: number
  decision: 'APPROVED' | 'REJECTED'
  reviewNote?: string
}

export interface OfflineMaterialRequest {
  version: number
  reason: string
}

function toDetail(data: Material): MaterialDetail {
  const { revisions, ...material } = data
  return { material, revisions: revisions ?? [] }
}

export async function listMaterials(query: MaterialListQuery): Promise<PageResult<Material>> {
  const res = await http.get<ApiResponse<PageResult<Material>>>('/materials', {
    params: {
      page: query.page,
      size: query.size,
      keyword: query.keyword || undefined,
      categoryCode: query.categoryCode || undefined,
      status: query.status || undefined,
      institutionId: query.institutionId,
      projectId: query.projectId,
      cityCode: query.cityCode || undefined,
      globalOnly: query.globalOnly,
    },
  })
  return res.data.data
}

export async function getMaterial(id: number): Promise<MaterialDetail> {
  const res = await http.get<ApiResponse<Material>>(`/materials/${id}`)
  return toDetail(res.data.data)
}

export async function createMaterial(body: CreateMaterialRequest, idempotencyKey: string): Promise<Material> {
  const res = await http.post<ApiResponse<Material>>('/materials', body, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
  return res.data.data
}

export async function updateMaterial(id: number, body: UpdateMaterialRequest, idempotencyKey: string): Promise<Material> {
  const res = await http.patch<ApiResponse<Material>>(`/materials/${id}`, body, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
  return res.data.data
}

export async function createMaterialRevision(
  id: number,
  body: CreateMaterialRevisionRequest,
  idempotencyKey: string,
): Promise<Material> {
  const res = await http.post<ApiResponse<Material>>(`/materials/${id}/revisions`, body, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
  return res.data.data
}

export async function submitMaterialRevision(
  id: number,
  revisionId: number,
  version: number,
  idempotencyKey: string,
): Promise<Material> {
  const res = await http.post<ApiResponse<Material>>(
    `/materials/${id}/revisions/${revisionId}/submit`,
    { version },
    { headers: { 'Idempotency-Key': idempotencyKey } },
  )
  return res.data.data
}

export async function reviewMaterialRevision(
  id: number,
  revisionId: number,
  body: ReviewMaterialRevisionRequest,
  idempotencyKey: string,
): Promise<Material> {
  const res = await http.post<ApiResponse<Material>>(
    `/materials/${id}/revisions/${revisionId}/review`,
    body,
    { headers: { 'Idempotency-Key': idempotencyKey } },
  )
  return res.data.data
}

export async function offlineMaterial(
  id: number,
  body: OfflineMaterialRequest,
  idempotencyKey: string,
): Promise<Material> {
  const res = await http.post<ApiResponse<Material>>(`/materials/${id}/offline`, body, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
  return res.data.data
}

export async function getMaterialRevisionDownload(id: number, revisionId: number): Promise<DownloadResponse> {
  const res = await http.get<ApiResponse<DownloadResponse>>(`/materials/${id}/revisions/${revisionId}/download`)
  return res.data.data
}
