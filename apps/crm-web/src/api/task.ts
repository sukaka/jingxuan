import type { ApiResponse, PageResult } from '../types/api'
import { http } from './http'

/**
 * 回访/待办任务 API(P0-1b 契约)。
 * status: OPEN/PROCESSING/DONE/CANCELED;priority: LOW/MEDIUM/HIGH/URGENT;taskType 走 taskTypes 字典。
 */
export interface TaskItem {
  id: number
  customerId: number | null
  originalAssigneeUserId?: number | null
  assigneeUserId: number | null
  taskTeamId?: number | null
  taskType: string
  priority: string
  status: string
  title: string
  description: string | null
  dueAt: string | null
  claimedAt?: string | null
  completedAt?: string | null
  createdAt: string
  version: number
}

/** assigneeId 只允许本人任务/收窄范围(后端强制),不能借参数看他人任务 */
export interface TaskListQuery {
  page: number
  size: number
  assigneeId?: number
  status?: string
}

export interface CreateTaskRequest {
  customerId?: number
  taskType: string
  priority: string
  title: string
  description?: string
  dueAt?: string
}

/** 通用 PATCH 仅修改资料；状态、处理人和团队必须走独立动作接口。 */
export interface UpdateTaskRequest {
  taskType?: string
  priority?: string
  title?: string
  description?: string
  dueAt?: string
  version: number
}

export interface ReassignTaskRequest {
  assigneeUserId: number
  reason: string
  version: number
}

export interface ReturnTaskRequest {
  reason: string
  version: number
}

export interface TaskActionLog {
  id: number
  taskId: number
  action: 'CLAIM' | 'REASSIGN' | 'RETURN' | 'COMPLETE' | string
  fromStatus: string | null
  toStatus: string | null
  actorUserId: number | null
  fromAssigneeUserId?: number | null
  toAssigneeUserId?: number | null
  fromTaskTeamId?: number | null
  toTaskTeamId?: number | null
  reason: string | null
  createdAt: string
}

/** GET /tasks(task:read) */
export async function listTasks(query: TaskListQuery): Promise<PageResult<TaskItem>> {
  const res = await http.get<ApiResponse<PageResult<TaskItem>>>('/tasks', {
    params: {
      page: query.page,
      size: query.size,
      assigneeId: query.assigneeId ?? undefined,
      status: query.status || undefined,
    },
  })
  return res.data.data
}

/** POST /tasks(task:write) */
export async function createTask(request: CreateTaskRequest): Promise<TaskItem> {
  const res = await http.post<ApiResponse<TaskItem>>('/tasks', request)
  return res.data.data
}

/** PATCH /tasks/{id}(task:write,本人任务),version 乐观锁 */
export async function updateTask(id: number, request: UpdateTaskRequest): Promise<TaskItem> {
  const res = await http.patch<ApiResponse<TaskItem>>(`/tasks/${id}`, request)
  return res.data.data
}

async function postTaskAction(id: number, action: string, body: unknown, idempotencyKey: string): Promise<TaskItem> {
  const res = await http.post<ApiResponse<TaskItem>>(`/tasks/${id}/${action}`, body, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
  return res.data.data
}

export function claimTask(id: number, version: number, idempotencyKey: string = crypto.randomUUID()): Promise<TaskItem> {
  return postTaskAction(id, 'claim', { version }, idempotencyKey)
}

export function reassignTask(id: number, request: ReassignTaskRequest, idempotencyKey: string = crypto.randomUUID()): Promise<TaskItem> {
  return postTaskAction(id, 'reassign', request, idempotencyKey)
}

export function returnTask(id: number, request: ReturnTaskRequest, idempotencyKey: string = crypto.randomUUID()): Promise<TaskItem> {
  return postTaskAction(id, 'return', request, idempotencyKey)
}

export function completeTask(id: number, version: number, idempotencyKey: string = crypto.randomUUID()): Promise<TaskItem> {
  return postTaskAction(id, 'complete', { version }, idempotencyKey)
}

export async function listTaskActions(id: number): Promise<TaskActionLog[]> {
  const res = await http.get<ApiResponse<TaskActionLog[]>>(`/tasks/${id}/actions`)
  return res.data.data
}
