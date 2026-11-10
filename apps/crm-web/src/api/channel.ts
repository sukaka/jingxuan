import type { ApiResponse, PageResult } from '../types/api'
import { http } from './http'

export type ChannelStatus = 'ACTIVE' | 'INACTIVE' | 'ARCHIVED'
export type ChannelType = 'BLOGGER' | 'DISTRIBUTION' | 'REFERRAL'

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
  idCardNumber: string | null
  settlementMethod: string | null
  cooperationStartDate: string | null
  cooperationEndDate: string | null
  paymentAccountName: string | null
  paymentAccountNo: string | null
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

export interface ChannelOwnerOption {
  id: number
  displayName: string
}

export async function listChannelOwnerOptions(): Promise<ChannelOwnerOption[]> {
  const res = await http.get<ApiResponse<ChannelOwnerOption[]>>('/channels/owner-options')
  return res.data.data
}
