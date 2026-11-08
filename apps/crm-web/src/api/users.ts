import type { ApiResponse, PageResult } from '../types/api'
import { http } from './http'

/** 用户管理列表项(字段与 sys_user 对齐,roles 为角色 code 列表;status: ACTIVE/DISABLED) */
export interface ManagedUser {
  id: number
  username: string
  displayName: string
  userType: string
  status: string
  teamId: number | null
  /** 团队名(后端联表 sys_team 返回,可能为空) */
  teamName?: string | null
  roles: string[]
  lastLoginAt: string | null
  createdAt: string
  version: number
}

export interface UserListQuery {
  page: number
  size: number
  keyword?: string
  status?: string
  userType?: string
  teamId?: number | ''
  roleCode?: string
}

export interface TeamOption {
  id: number
  teamName: string
}

export interface CreateUserRequest {
  username: string
  displayName: string
  password: string
  userType: string
  teamId?: number | null
  roleIds?: number[]
}

/** PATCH /users/{id}:body.version 乐观锁,冲突返回 409 */
export interface UpdateUserRequest {
  displayName?: string
  teamId?: number | null
  version: number
}

/** GET /users(user:read) */
export async function listUsers(query: UserListQuery): Promise<PageResult<ManagedUser>> {
  const res = await http.get<ApiResponse<PageResult<ManagedUser>>>('/users', {
    params: {
      page: query.page,
      size: query.size,
      keyword: query.keyword || undefined,
      status: query.status || undefined,
      userType: query.userType || undefined,
      teamId: query.teamId === '' ? undefined : query.teamId,
      roleCode: query.roleCode || undefined,
    },
  })
  return res.data.data
}

/** GET /users/teams(user:read)：当前租户启用团队选项 */
export async function listUserTeams(): Promise<TeamOption[]> {
  const res = await http.get<ApiResponse<TeamOption[]>>('/users/teams')
  return res.data.data
}

/** POST /users(user:write)。密码仅创建时提交,后端 BCrypt 存储 */
export async function createUser(request: CreateUserRequest): Promise<ManagedUser> {
  const res = await http.post<ApiResponse<ManagedUser>>('/users', request)
  return res.data.data
}

/** PATCH /users/{id}(user:write),携带 version 乐观锁 */
export async function updateUser(id: number, request: UpdateUserRequest): Promise<ManagedUser> {
  const res = await http.patch<ApiResponse<ManagedUser>>(`/users/${id}`, request)
  return res.data.data
}

/** PUT /users/{id}/roles(user:write),全量替换角色 */
export async function updateUserRoles(id: number, roleIds: number[]): Promise<ManagedUser> {
  const res = await http.put<ApiResponse<ManagedUser>>(`/users/${id}/roles`, { roleIds })
  return res.data.data
}

/** POST /users/{id}/disable(user:write),停用后强制退出并回收权限 */
export async function disableUser(id: number): Promise<ManagedUser> {
  const res = await http.post<ApiResponse<ManagedUser>>(`/users/${id}/disable`)
  return res.data.data
}
