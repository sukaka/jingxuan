import type { ApiResponse, PageResult } from '../types/api'
import { http } from './http'

/** 审计日志条目(before/after 为后端脱敏后的快照 JSON) */
export interface AuditLogItem {
  id: number
  actorUserId: number | null
  actionCode: string
  targetType: string
  targetId: string
  beforeJson: unknown
  afterJson: unknown
  traceId: string | null
  clientIp: string | null
  reason: string | null
  createdAt: string
}

export interface AuditLogQuery {
  page: number
  size: number
  actorId?: number | null
  actionCode?: string
  targetType?: string
}

/** GET /audit-logs(audit:read)。审计日志只读,不提供任何编辑入口 */
export async function listAuditLogs(query: AuditLogQuery): Promise<PageResult<AuditLogItem>> {
  const res = await http.get<ApiResponse<PageResult<AuditLogItem>>>('/audit-logs', {
    params: {
      page: query.page,
      size: query.size,
      actorId: query.actorId ?? undefined,
      actionCode: query.actionCode || undefined,
      targetType: query.targetType || undefined,
    },
  })
  return res.data.data
}
