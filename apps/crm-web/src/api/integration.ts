import type { ApiResponse } from '../types/api'
import { http } from './http'

export interface IntegrationRuntimeConfig {
  youzanRealEnabled: boolean
  wecomArchiveRealEnabled: boolean
  wecomOfficialEnabled: boolean
  version: number
  source: 'DATABASE' | 'ENVIRONMENT'
}

export interface IntegrationRuntimeConfigInput {
  youzanRealEnabled: boolean
  wecomArchiveRealEnabled: boolean
  wecomOfficialEnabled: boolean
  version: number
}

export async function getIntegrationRuntimeConfig(): Promise<IntegrationRuntimeConfig> {
  const res = await http.get<ApiResponse<IntegrationRuntimeConfig>>('/integrations/runtime-config')
  return res.data.data
}

export async function saveIntegrationRuntimeConfig(
  body: IntegrationRuntimeConfigInput,
  key: string,
): Promise<IntegrationRuntimeConfig> {
  const res = await http.put<ApiResponse<IntegrationRuntimeConfig>>('/integrations/runtime-config', body, {
    headers: { 'Idempotency-Key': key },
  })
  return res.data.data
}
