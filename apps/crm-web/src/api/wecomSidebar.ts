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
