import type { ApiResponse } from '../types/api'
import { http } from './http'

export interface WecomMapping {
  id: number
  corpId: string
  wecomUserid: string
  staffUserId: number
  archiveEnabled: boolean
  messageEnabled: boolean
  version: number
}

export interface WecomMappingInput {
  corpId: string
  wecomUserid: string
  staffUserId: number
  messageEnabled: boolean
  reason: string
}

export type WecomMappingUpdateInput = WecomMappingInput & { version: number }

export async function listWecomMappings(): Promise<WecomMapping[]> {
  const res = await http.get<ApiResponse<WecomMapping[]>>('/notification-settings/wecom-mappings')
  return res.data.data
}

export async function createWecomMapping(body: WecomMappingInput, key: string): Promise<WecomMapping> {
  const res = await http.post<ApiResponse<WecomMapping>>('/notification-settings/wecom-mappings', body, {
    headers: { 'Idempotency-Key': key },
  })
  return res.data.data
}

export async function updateWecomMapping(id: number, body: WecomMappingUpdateInput, key: string): Promise<WecomMapping> {
  const res = await http.put<ApiResponse<WecomMapping>>(`/notification-settings/wecom-mappings/${id}`, body, {
    headers: { 'Idempotency-Key': key },
  })
  return res.data.data
}
