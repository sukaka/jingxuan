import type { ApiResponse, PageResult } from '../types/api'
import { http } from './http'

/**
 * 每日跟进 API(P0-1b 契约)。每次跟进追加新流水,误录通过 DELETE 软删并保留审计。
 * estimatedAmountFen 为整数分,前端展示层 /100 转元。
 */
export interface FollowupItem {
  id: number
  customerId: number
  /** 联表展示用客户称呼/昵称,后端可能不返回 */
  customerName?: string | null
  ownerStaffId: number
  /** 联表 sys_user 的管家姓名,后端可能不返回 */
  ownerStaffName?: string | null
  channelCode: string
  contactAt: string | null
  summary: string | null
  intentProject: string | null
  estimatedAmountFen: number
  nextFollowupAt: string | null
  blockerNote: string | null
  createdBy: number
  createdAt: string
  version: number
}

export interface FollowupListQuery {
  page: number
  size: number
  customerId?: number
}

export interface CreateFollowupRequest {
  customerId: number
  channelCode: string
  contactAt?: string
  summary?: string
  intentProject?: string
  estimatedAmountFen?: number
  nextFollowupAt?: string
  blockerNote?: string
}

/** PATCH /followups/{id}:创建者或同组主管可改;version 乐观锁 */
export interface UpdateFollowupRequest {
  channelCode?: string
  contactAt?: string
  summary?: string
  intentProject?: string
  estimatedAmountFen?: number
  nextFollowupAt?: string
  blockerNote?: string
  version: number
}

/** GET /followups(followup:read),按数据范围过滤 */
export async function listFollowups(query: FollowupListQuery): Promise<PageResult<FollowupItem>> {
  const res = await http.get<ApiResponse<PageResult<FollowupItem>>>('/followups', {
    params: {
      page: query.page,
      size: query.size,
      customerId: query.customerId ?? undefined,
    },
  })
  return res.data.data
}

/** POST /followups(followup:write);创建后客户 last_followup_at 由后端依真实跟进计算 */
export async function createFollowup(request: CreateFollowupRequest): Promise<FollowupItem> {
  const res = await http.post<ApiResponse<FollowupItem>>('/followups', request)
  return res.data.data
}

/** PATCH /followups/{id}(followup:write;创建者或同组主管),version 乐观锁 */
export async function updateFollowup(
  id: number,
  request: UpdateFollowupRequest,
): Promise<FollowupItem> {
  const res = await http.patch<ApiResponse<FollowupItem>>(`/followups/${id}`, request)
  return res.data.data
}

/** DELETE /followups/{id}?version=：误录软删除，服务端校验权限与版本 */
export async function deleteFollowup(id: number, version: number): Promise<void> {
  await http.delete<ApiResponse<void>>(`/followups/${id}`, { params: { version } })
}
