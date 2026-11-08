import type { ApiResponse, PageResult } from '../types/api'
import { http } from './http'

export type CampaignType = 'REGULAR' | 'FLASH' | 'PROMOTION'
export type CampaignStatus = 'DRAFT' | 'ACTIVE' | 'INACTIVE' | 'ARCHIVED'
export type CampaignBatchStatus = 'DRAFT' | 'ACTIVE' | 'ARCHIVED'

export interface Campaign {
  id: number
  campaignCode: string
  campaignName: string
  campaignType: CampaignType
  startsAt: string | null
  endsAt: string | null
  status: CampaignStatus
  createdAt: string
  version: number
}

export interface CampaignBatch {
  id: number
  campaignId: number
  batchCode: string
  batchName: string
  startsAt: string | null
  endsAt: string | null
  status: CampaignBatchStatus
  createdAt: string
  version: number
}

export type CampaignDetail = Campaign & { batches: CampaignBatch[] }

export interface CampaignListQuery {
  page: number
  size: number
  keyword?: string
  status?: string
  campaignType?: string
}

export interface CreateCampaignInput {
  campaignCode: string
  campaignName: string
  campaignType: CampaignType
  startsAt: string | null
  endsAt: string | null
  status: 'DRAFT' | 'ACTIVE' | 'INACTIVE'
}

export interface UpdateCampaignInput {
  campaignName: string
  campaignType: CampaignType
  startsAt: string | null
  endsAt: string | null
  status: CampaignStatus
  version: number
}

export interface CreateCampaignBatchInput {
  batchCode: string
  batchName: string
  startsAt: string | null
  endsAt: string | null
  status: 'DRAFT' | 'ACTIVE'
}

export interface UpdateCampaignBatchInput {
  batchName: string
  startsAt: string | null
  endsAt: string | null
  status: CampaignBatchStatus
  version: number
}

export async function listCampaigns(query: CampaignListQuery): Promise<PageResult<Campaign>> {
  const res = await http.get<ApiResponse<PageResult<Campaign>>>('/campaigns', { params: query })
  return res.data.data
}

export async function getCampaign(id: number): Promise<CampaignDetail> {
  const res = await http.get<ApiResponse<CampaignDetail>>(`/campaigns/${id}`)
  return res.data.data
}

export async function createCampaign(body: CreateCampaignInput, key: string): Promise<Campaign> {
  const res = await http.post<ApiResponse<Campaign>>('/campaigns', body, { headers: { 'Idempotency-Key': key } })
  return res.data.data
}

export async function updateCampaign(id: number, body: UpdateCampaignInput, key: string): Promise<Campaign> {
  const res = await http.patch<ApiResponse<Campaign>>(`/campaigns/${id}`, body, { headers: { 'Idempotency-Key': key } })
  return res.data.data
}

export async function createCampaignBatch(campaignId: number, body: CreateCampaignBatchInput, key: string): Promise<CampaignBatch> {
  const res = await http.post<ApiResponse<CampaignBatch>>(`/campaigns/${campaignId}/batches`, body, { headers: { 'Idempotency-Key': key } })
  return res.data.data
}

export async function updateCampaignBatch(campaignId: number, batchId: number, body: UpdateCampaignBatchInput, key: string): Promise<CampaignBatch> {
  const res = await http.patch<ApiResponse<CampaignBatch>>(`/campaigns/${campaignId}/batches/${batchId}`, body, { headers: { 'Idempotency-Key': key } })
  return res.data.data
}
