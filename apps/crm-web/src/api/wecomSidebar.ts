import type { ApiResponse } from '../types/api'
import type { CustomerDetail } from './customer'
import { http } from './http'

export interface WecomSdkSignature {
  nonceStr: string
  timestamp: number
  signature: string
}

export interface WecomSdkSignatureBundle {
  corpId: string
  agentId: number
  url: string
  config: WecomSdkSignature
  agentConfig: WecomSdkSignature
}

export interface WecomSidebarContext {
  contextStatus: 'READY' | 'CUSTOMER_UNLINKED' | 'WAITING_CONTEXT'
  corpId: string
  externalUserid: string | null
  employee: {
    userId: number
    displayName: string
  }
  customer: CustomerDetail | null
  summary: {
    totalOrders: number | null
    remainingPackages: number | null
    remainingCount: number | null
    lastFollowupAt: string | null
  }
}

export interface WecomSidebarGroupRoom {
  roomId: string
  displayName: string | null
  status: string
  staffCount: number
  externalCount: number
  linkedCustomerCount: number
}

export interface WecomSidebarGroupCustomer {
  customerId: number
  customerNo: string
  displayName: string | null
  nickname: string | null
  externalUserid: string
  tierCode: string | null
  ownerName: string | null
  mobileMasked: string | null
}

export interface WecomSidebarGroupContext {
  contextStatus: 'READY' | 'ROOM_UNREGISTERED' | 'WAITING_CONTEXT'
  corpId: string
  chatId: string | null
  employee: {
    userId: number
    displayName: string
  }
  room: WecomSidebarGroupRoom | null
  customers: WecomSidebarGroupCustomer[]
}

export interface WecomSidebarLinkContactInput {
  externalUserid: string
  customerId: number
  reason?: string
}

export interface WecomSidebarLinkContactResult {
  contactId: number
  corpId: string
  externalUserid: string
  staffUserId: number
  customerId: number
  linkType: string
  linkReason: string
  alreadyLinked: boolean
}

export interface WecomMaterialMediaResult {
  materialId: number
  revisionId: number
  fileId: number
  mediaId: string
  mediaType: 'image' | 'file'
  fileName: string
  contentType: string
  sizeBytes: number
  cached: boolean
  expiresAt: string
}

export async function getWecomSidebarSignature(url: string): Promise<WecomSdkSignatureBundle> {
  const res = await http.post<ApiResponse<WecomSdkSignatureBundle>>('/wecom/jssdk/signature', { url })
  return res.data.data
}

export async function getWecomSidebarContext(externalUserid?: string): Promise<WecomSidebarContext> {
  const res = await http.get<ApiResponse<WecomSidebarContext>>('/wecom/sidebar/context', {
    params: externalUserid ? { externalUserid } : {},
  })
  return res.data.data
}

export async function getWecomSidebarGroupContext(chatId?: string): Promise<WecomSidebarGroupContext> {
  const res = await http.get<ApiResponse<WecomSidebarGroupContext>>('/wecom/sidebar/group-context', {
    params: chatId ? { chatId } : {},
  })
  return res.data.data
}

export async function linkWecomSidebarContact(
  input: WecomSidebarLinkContactInput,
): Promise<WecomSidebarLinkContactResult> {
  const res = await http.post<ApiResponse<WecomSidebarLinkContactResult>>(
    '/wecom/sidebar/contacts/link',
    input,
  )
  return res.data.data
}

export async function uploadWecomMaterialMedia(materialId: number): Promise<WecomMaterialMediaResult> {
  const res = await http.post<ApiResponse<WecomMaterialMediaResult>>(
    `/wecom/sidebar/materials/${materialId}/media`,
  )
  return res.data.data
}
