import type { ApiResponse, PageResult } from '../types/api'
import { http } from './http'

export type ChannelStatus = 'ACTIVE' | 'INACTIVE' | 'ARCHIVED'
export type ChannelType = 'BLOGGER' | 'DISTRIBUTION' | 'REFERRAL'
export type ChannelChangeRequestStatus = 'PENDING_REVIEW' | 'APPROVED' | 'REJECTED' | 'CANCELLED' | 'CONFLICT_REVALIDATE'

export interface Channel {
  id: number
  channelNo: string
  name: string
  channelType: ChannelType
  platformCode: string | null
  commissionPercent: number | null
  businessOwnerUserId: number | null
  businessOwnerName: string | null
  realName: string | null
  contactPhone: string | null
  contactPhoneConfigured?: boolean
  idCardNumber: string | null
  idCardConfigured?: boolean
  settlementMethod: string | null
  cooperationStartDate: string | null
  cooperationEndDate: string | null
  paymentAccountName: string | null
  paymentAccountNo: string | null
  paymentAccountConfigured?: boolean
  paymentBankName: string | null
  status: ChannelStatus
  remarks: string | null
  createdAt: string
  version: number
}

export interface ChannelProfileFields {
  commissionPercent?: number | null
  realName?: string | null
  contactPhone?: string | null
  idCardNumber?: string | null
  settlementMethod?: string | null
  cooperationStartDate?: string | null
  cooperationEndDate?: string | null
  paymentAccountName?: string | null
  paymentAccountNo?: string | null
  paymentBankName?: string | null
}

export interface ChannelListQuery {
  page: number
  size: number
  keyword?: string
  status?: ChannelStatus
  channelType?: ChannelType
}

export interface CreateChannelRequest extends ChannelProfileFields {
  channelNo?: string
  name: string
  channelType: ChannelType
  platformCode?: string
  businessOwnerUserId?: number
  status?: 'ACTIVE' | 'INACTIVE'
  remarks?: string
}

export interface UpdateChannelRequest extends ChannelProfileFields {
  channelNo?: string
  name: string
  channelType: ChannelType
  platformCode: string | null
  businessOwnerUserId: number | null
  status: ChannelStatus
  remarks: string | null
  version: number
}

export interface ToggleChannelStatusRequest {
  status: 'ACTIVE' | 'INACTIVE'
  version: number
  reason?: string
}

export interface CreateChannelFinancialChangeRequest {
  commissionPercent?: number | null
  settlementMethod?: string | null
  paymentAccountName?: string | null
  paymentAccountNo?: string | null
  paymentBankName?: string | null
  baseVersion: number
  reason: string
}

export interface ChannelFinancialChangeRequest {
  id: number
  channelId: number
  channelNo?: string | null
  channelName?: string | null
  status: ChannelChangeRequestStatus
  requestedBy: number
  requestedByName?: string | null
  requestedAt: string
  reason: string
  baseVersion: number
  version: number
  beforeMasked?: Record<string, string | number | null> | string | null
  afterMasked?: Record<string, string | number | null> | string | null
  commissionPercent?: number | null
  settlementMethod?: string | null
  paymentAccountName?: string | null
  paymentAccountNo?: string | null
  paymentBankName?: string | null
  reviewerUserId?: number | null
  reviewerName?: string | null
  reviewedAt?: string | null
  reviewComment?: string | null
}

export interface ReviewChannelFinancialChangeRequest {
  decision: 'APPROVE' | 'REJECT'
  version: number
  comment?: string
}

export interface ChannelChangeRequestListQuery {
  page: number
  size: number
  channelId?: number
  status?: ChannelChangeRequestStatus | ''
}

export async function listChannels(query: ChannelListQuery): Promise<PageResult<Channel>> {
  const res = await http.get<ApiResponse<PageResult<Channel>>>('/channels', {
    params: {
      page: query.page,
      size: query.size,
      keyword: query.keyword || undefined,
      status: query.status || undefined,
      channelType: query.channelType || undefined,
    },
  })
  return res.data.data
}

export async function createChannel(request: CreateChannelRequest): Promise<Channel> {
  const res = await http.post<ApiResponse<Channel>>('/channels', request)
  return res.data.data
}

export async function updateChannel(id: number, request: UpdateChannelRequest): Promise<Channel> {
  const res = await http.patch<ApiResponse<Channel>>(`/channels/${id}`, request)
  return res.data.data
}

export async function toggleChannelStatus(id: number, request: ToggleChannelStatusRequest): Promise<Channel> {
  const res = await http.post<ApiResponse<Channel>>(`/channels/${id}/status`, request)
  return res.data.data
}

export async function createChannelFinancialChangeRequest(
  channelId: number,
  request: CreateChannelFinancialChangeRequest,
  idempotencyKey: string,
): Promise<ChannelFinancialChangeRequest> {
  const res = await http.post<ApiResponse<ChannelFinancialChangeRequest>>(
    `/channels/${channelId}/financial-change-requests`,
    request,
    { headers: { 'Idempotency-Key': idempotencyKey } },
  )
  return res.data.data
}

export async function listChannelFinancialChangeRequests(
  channelId: number,
  query: { page?: number; size?: number; status?: string } = {},
): Promise<PageResult<ChannelFinancialChangeRequest>> {
  const res = await http.get<ApiResponse<PageResult<ChannelFinancialChangeRequest>>>(
    `/channels/${channelId}/financial-change-requests`,
    { params: { page: query.page ?? 1, size: query.size ?? 20, status: query.status || undefined } },
  )
  return res.data.data
}

export async function listAllChannelFinancialChangeRequests(
  query: ChannelChangeRequestListQuery,
): Promise<PageResult<ChannelFinancialChangeRequest>> {
  const res = await http.get<ApiResponse<PageResult<ChannelFinancialChangeRequest>>>(
    '/channel-change-requests',
    {
      params: {
        page: query.page,
        size: query.size,
        channelId: query.channelId || undefined,
        status: query.status || undefined,
      },
    },
  )
  return res.data.data
}

export async function reviewChannelFinancialChangeRequest(
  requestId: number,
  request: ReviewChannelFinancialChangeRequest,
  idempotencyKey?: string,
): Promise<ChannelFinancialChangeRequest> {
  const res = await http.post<ApiResponse<ChannelFinancialChangeRequest>>(
    `/channel-change-requests/${requestId}/review`,
    request,
    idempotencyKey ? { headers: { 'Idempotency-Key': idempotencyKey } } : undefined,
  )
  return res.data.data
}

export interface ChannelOwnerOption {
  id: number
  displayName: string
}

export async function listChannelOwnerOptions(): Promise<ChannelOwnerOption[]> {
  const res = await http.get<ApiResponse<ChannelOwnerOption[]>>('/channels/owner-options')
  return res.data.data
}
