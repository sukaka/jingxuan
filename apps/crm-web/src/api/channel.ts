import type { ApiResponse, PageResult } from '../types/api'
import { http } from './http'

export type ChannelStatus = 'ACTIVE' | 'INACTIVE' | 'ARCHIVED'

export interface Channel {
  id: number
  channelNo: string
  name: string
  platformCode: string | null
  businessOwnerUserId: number | null
  status: ChannelStatus
  remarks: string | null
  createdAt: string
  version: number
}

export interface ChannelListQuery {
  page: number
  size: number
  keyword?: string
  status?: ChannelStatus
}

export interface CreateChannelRequest {
  channelNo: string
  name: string
  platformCode?: string
  businessOwnerUserId?: number
  status?: 'ACTIVE' | 'INACTIVE'
  remarks?: string
}

export interface UpdateChannelRequest {
  channelNo?: string
  name: string
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
