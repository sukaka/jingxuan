import type { ApiResponse } from '../types/api'
import { http } from './http'

export interface WecomRoomParticipant {
  wecomUserid: string
  participantType: 'STAFF' | 'EXTERNAL'
  staffUserId: number | null
  externalUserid: string | null
  status: 'ACTIVE' | 'REMOVED'
}

export interface WecomRoom {
  corpId: string
  roomId: string
  displayName: string | null
  enabled: boolean
  archivable: boolean
  membershipConfirmedAt: string
  version: number
  participants: WecomRoomParticipant[]
}

export interface WecomRoomInput {
  corpId: string
  displayName?: string
  enabled: boolean
  memberUserids: string[]
  sourceEvidenceRef: string
  version: number
  reason: string
}

export async function listWecomRooms(corpId: string): Promise<WecomRoom[]> {
  const response = await http.get<ApiResponse<WecomRoom[]>>('/integrations/wecom/rooms', { params: { corpId } })
  return response.data.data
}

export async function saveWecomRoom(roomId: string, body: WecomRoomInput, idempotencyKey: string): Promise<WecomRoom> {
  const response = await http.put<ApiResponse<WecomRoom>>(`/integrations/wecom/rooms/${encodeURIComponent(roomId)}`, body, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
  return response.data.data
}
