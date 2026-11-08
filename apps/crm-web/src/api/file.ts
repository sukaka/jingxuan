import type { ApiResponse } from '../types/api'
import { ApiError } from '../types/api'
import { http } from './http'

export type FileClassification = 'CONTRACT' | 'QUALIFICATION' | 'CHANGE_EVIDENCE' | 'PAYMENT' | 'REDEMPTION' | 'MATERIAL' | 'MERCHANT_RESOURCE'

export interface UploadInitInput {
  originalName: string
  mimeType: string
  sizeBytes: number
  sha256: string
  classification: FileClassification
  institutionId?: number
}

export interface UploadInitResponse {
  fileId: number
  objectKey: string
  uploadUrl: string
  expiresAt: string
  method: string
  sha256: string
}

export interface FileMetadata {
  id: number
  objectKey: string
  originalName: string
  mimeType: string
  sizeBytes: number
  sha256: string
  classification: FileClassification
  institutionId: number | null
  scanStatus: string
  uploadedBy: number
  etag: string | null
  lastError: string | null
  expiresAt: string | null
  createdAt: string
  updatedAt: string
  version: number
}

export interface DownloadResponse {
  fileId: number
  downloadUrl: string
  expiresAt: string
}

export interface PresignedPutInput {
  url: string
  headers?: Record<string, string>
  file: Blob
}

export async function sha256File(file: Blob): Promise<string> {
  const digest = await globalThis.crypto.subtle.digest('SHA-256', await file.arrayBuffer())
  return Array.from(new Uint8Array(digest), (value) => value.toString(16).padStart(2, '0')).join('')
}

export async function initFileUpload(body: UploadInitInput): Promise<UploadInitResponse> {
  const res = await http.post<ApiResponse<UploadInitResponse>>('/files/upload-init', body)
  return res.data.data
}

export async function putPresignedFile(input: PresignedPutInput): Promise<string> {
  let response: Response
  try {
    response = await fetch(input.url, {
      method: 'PUT',
      headers: input.headers,
      body: input.file,
    })
  } catch (error) {
    throw new ApiError({
      status: 0,
      code: 'PRESIGNED_UPLOAD_FAILED',
      message: error instanceof Error ? error.message : '预签名文件上传失败',
    })
  }
  if (!response.ok) {
    const responseText = await response.text().catch(() => '')
    throw new ApiError({
      status: response.status,
      code: 'PRESIGNED_UPLOAD_FAILED',
      message: responseText.slice(0, 200) || `预签名文件上传失败（HTTP ${response.status}）`,
    })
  }
  const etag = response.headers.get('ETag')
  if (!etag) {
    throw new ApiError({ status: 0, code: 'PRESIGNED_ETAG_MISSING', message: '上传响应缺少真实 ETag，不能完成文件登记' })
  }
  return etag
}

export async function completeFileUpload(body: { fileId: number; etag: string }): Promise<FileMetadata> {
  const res = await http.post<ApiResponse<FileMetadata>>('/files/upload-complete', body)
  return res.data.data
}

export async function getFileMetadata(id: number): Promise<FileMetadata> {
  const res = await http.get<ApiResponse<FileMetadata>>(`/files/${id}`)
  return res.data.data
}

export async function getFileDownload(id: number): Promise<DownloadResponse> {
  const res = await http.get<ApiResponse<DownloadResponse>>(`/files/${id}/download`)
  return res.data.data
}

export async function waitForFileClean(
  id: number,
  options: { maxAttempts?: number; intervalMs?: number; sleep?: (ms: number) => Promise<void>; fetchMetadata?: (id: number) => Promise<FileMetadata> } = {},
): Promise<FileMetadata> {
  const maxAttempts = options.maxAttempts ?? 20
  const intervalMs = options.intervalMs ?? 1000
  const sleep = options.sleep ?? ((ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms)))
  const fetchMetadata = options.fetchMetadata ?? getFileMetadata
  for (let attempt = 0; attempt < maxAttempts; attempt++) {
    const metadata = await fetchMetadata(id)
    if (metadata.scanStatus === 'CLEAN') return metadata
    if (metadata.scanStatus === 'BLOCKED' || metadata.scanStatus === 'ERROR') {
      throw new ApiError({ status: 409, code: 'FILE_SCAN_FAILED', message: metadata.lastError || '文件未通过安全扫描' })
    }
    if (attempt < maxAttempts - 1) await sleep(intervalMs)
  }
  throw new ApiError({ status: 408, code: 'FILE_SCAN_PENDING', message: '安全扫描仍在进行，请稍后重试查询' })
}
