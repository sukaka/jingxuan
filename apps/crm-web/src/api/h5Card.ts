import type { ApiResponse } from '../types/api'
import { http } from './http'

export interface H5Card {
  id: number
  customerId: number
  projectId: number
  projectName: string
  title: string
  summary: string
  content: string
  link: string
  imageUrl: string
  status: 'ACTIVE' | 'DISABLED'
  createdAt: string
  version: number
}

export interface H5CardInput {
  customerId: number
  projectId: number
  externalUserid: string
  title: string
  summary: string
  content: string
}

export interface PublicH5Card {
  projectName: string
  title: string
  summary: string
  content: string
  imageUrl: string
}

function idempotency(key: string) {
  return { headers: { 'Idempotency-Key': key } }
}

export async function createH5Card(body: H5CardInput, key: string): Promise<H5Card> {
  const res = await http.post<ApiResponse<H5Card>>('/h5/cards', body, idempotency(key))
  return res.data.data
}

export async function listH5Cards(customerId: number, size = 20): Promise<H5Card[]> {
  const res = await http.get<ApiResponse<H5Card[]>>('/h5/cards', { params: { customerId, size } })
  return res.data.data
}

export async function disableH5Card(id: number, body: { version: number; reason: string }, key: string): Promise<H5Card> {
  const res = await http.post<ApiResponse<H5Card>>(`/h5/cards/${id}/disable`, body, idempotency(key))
  return res.data.data
}

export async function recordH5CardUsage(id: number, body: {
  customerId: number
  sendStatus: 'COPIED' | 'SDK_SUCCESS' | 'SDK_FAILED' | 'SDK_UNKNOWN'
  clientResult?: string
}, key: string): Promise<void> {
  await http.post(`/h5/cards/${id}/usage`, body, idempotency(key))
}

export async function getPublicH5Card(shareKey: string): Promise<PublicH5Card> {
  const res = await http.get<ApiResponse<PublicH5Card>>(`/public/h5-cards/${shareKey}`)
  return res.data.data
}
