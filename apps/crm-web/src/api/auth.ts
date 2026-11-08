import type { ApiResponse } from '../types/api'
import { http } from './http'

/**
 * 登录/当前用户返回的会话主体。
 * 契约(P0-1 钉死):POST /auth/login 与 GET /auth/me 的 data 均为该结构,
 * roles 为全局角色 code 列表,permissions 为权限 code 列表;
 * institutionRoles 为机构ID与机构内角色的映射,旧会话缺失时按无机构角色处理。
 */
export interface AuthUser {
  userId: number
  username: string
  displayName: string
  userType: string
  roles: string[]
  permissions: string[]
  institutionRoles?: Record<number, string>
}

export interface LoginRequest {
  username: string
  password: string
  captchaId: string
  captchaAnswer: string
}

export interface CaptchaResponse {
  captchaId: string
  imageBase64: string
  expiresIn: number
}

export async function getCaptcha(): Promise<CaptchaResponse> {
  const res = await http.get<ApiResponse<CaptchaResponse>>('/auth/captcha')
  return res.data.data
}

/**
 * POST /auth/login。成功时后端 Set-Cookie:CRM_TOKEN(HttpOnly JWT)+ CRM_CSRF(可读随机串);
 * 失败抛 401 ApiError(code=UNAUTHORIZED,message=用户名或密码错误)。
 */
export async function login(request: LoginRequest): Promise<AuthUser> {
  const res = await http.post<ApiResponse<AuthUser>>('/auth/login', request)
  return res.data.data
}

/** POST /auth/logout。后端清 Cookie 并吊销登录会话(写审计);前端随后清空本地会话状态 */
export async function logout(): Promise<void> {
  await http.post<ApiResponse<null>>('/auth/logout')
}

/** GET /auth/me。每次实时解析角色权限(权限变更立即生效);未登录抛 401 */
export async function fetchMe(): Promise<AuthUser> {
  const res = await http.get<ApiResponse<AuthUser>>('/auth/me')
  return res.data.data
}
