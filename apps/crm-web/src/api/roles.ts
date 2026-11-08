import type { ApiResponse } from '../types/api'
import { http } from './http'

/** 角色(permissions 为该角色已授予的权限 code 列表) */
export interface RoleItem {
  id: number
  roleCode: string
  roleName: string
  status: string
  permissions: string[]
  version: number
}

/** 权限目录项(如 customer:read) */
export interface PermissionItem {
  id: number
  permissionCode: string
  description: string | null
}

export interface CreateRoleRequest {
  roleCode: string
  roleName: string
}

/** GET /roles(user:read) */
export async function listRoles(): Promise<RoleItem[]> {
  const res = await http.get<ApiResponse<RoleItem[]>>('/roles')
  return res.data.data
}

/** POST /roles(user:write) */
export async function createRole(request: CreateRoleRequest): Promise<RoleItem> {
  const res = await http.post<ApiResponse<RoleItem>>('/roles', request)
  return res.data.data
}

/** PUT /roles/{id}/permissions(user:write),全量替换角色权限,立即生效 */
export async function updateRolePermissions(id: number, permissionIds: number[]): Promise<RoleItem> {
  const res = await http.put<ApiResponse<RoleItem>>(`/roles/${id}/permissions`, { permissionIds })
  return res.data.data
}

/** GET /permissions(user:read),全量权限目录 */
export async function listPermissions(): Promise<PermissionItem[]> {
  const res = await http.get<ApiResponse<PermissionItem[]>>('/permissions')
  return res.data.data
}
