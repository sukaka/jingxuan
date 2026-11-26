import { ApiError } from '../types/api'

export const USERNAME_PATTERN = /^[A-Za-z0-9_.-]{3,32}$/
export const DISPLAY_NAME_MAX_LENGTH = 64
export const INITIAL_PASSWORD_MIN_LENGTH = 8
export const INITIAL_PASSWORD_MAX_LENGTH = 64

export type CreateUserField = 'username' | 'displayName' | 'password' | 'userType' | 'teamId' | 'roleIds'
export type EditUserField = 'displayName' | 'teamId'

export type CreateUserFieldErrors = Partial<Record<CreateUserField, string>>
export type EditUserFieldErrors = Partial<Record<EditUserField, string>>

export interface CreateUserValidationInput {
  username: string
  displayName: string
  password: string
  userType: string
}

export interface EditUserValidationInput {
  displayName: string
}

export function validateCreateUserInput(input: CreateUserValidationInput): {
  valid: boolean
  fieldErrors: CreateUserFieldErrors
} {
  const fieldErrors: CreateUserFieldErrors = {}

  if (!input.username || !input.username.trim()) {
    fieldErrors.username = '登录名不能为空'
  } else if (!USERNAME_PATTERN.test(input.username)) {
    fieldErrors.username = '登录名需为 3-32 位英文字母、数字或 ._-（不支持中文和空格）'
  }

  if (!input.displayName || !input.displayName.trim()) {
    fieldErrors.displayName = '姓名不能为空'
  } else if (input.displayName.length > DISPLAY_NAME_MAX_LENGTH) {
    fieldErrors.displayName = `姓名最长 ${DISPLAY_NAME_MAX_LENGTH} 个字符`
  }

  if (!input.password) {
    fieldErrors.password = '初始密码不能为空'
  } else if (!input.password.trim()) {
    fieldErrors.password = '初始密码不能为空白字符'
  } else if (
    input.password.length < INITIAL_PASSWORD_MIN_LENGTH
    || input.password.length > INITIAL_PASSWORD_MAX_LENGTH
  ) {
    fieldErrors.password = `初始密码长度须为 ${INITIAL_PASSWORD_MIN_LENGTH}-${INITIAL_PASSWORD_MAX_LENGTH} 位`
  }

  if (input.userType !== 'EMPLOYEE' && input.userType !== 'INSTITUTION') {
    fieldErrors.userType = '用户类型仅支持员工或机构用户'
  }

  return {
    valid: Object.keys(fieldErrors).length === 0,
    fieldErrors,
  }
}

export function validateEditUserInput(input: EditUserValidationInput): {
  valid: boolean
  fieldErrors: EditUserFieldErrors
} {
  const fieldErrors: EditUserFieldErrors = {}

  if (!input.displayName || !input.displayName.trim()) {
    fieldErrors.displayName = '姓名不能为空'
  } else if (input.displayName.length > DISPLAY_NAME_MAX_LENGTH) {
    fieldErrors.displayName = `姓名最长 ${DISPLAY_NAME_MAX_LENGTH} 个字符`
  }

  return {
    valid: Object.keys(fieldErrors).length === 0,
    fieldErrors,
  }
}

/**
 * 解析后端 ApiResponse.ErrorDetail[]（{ field, reason }），返回按字段归并的首条原因。
 */
export function extractApiFieldErrors(error: ApiError): Record<string, string> {
  if (!Array.isArray(error.details)) return {}
  const result: Record<string, string> = {}
  for (const item of error.details) {
    if (!item || typeof item !== 'object') continue
    const detail = item as { field?: unknown; reason?: unknown; message?: unknown }
    const field = typeof detail.field === 'string' ? detail.field.trim() : ''
    const reason = typeof detail.reason === 'string'
      ? detail.reason.trim()
      : (typeof detail.message === 'string' ? detail.message.trim() : '')
    if (!field || !reason || result[field]) continue
    result[field] = reason
  }
  return result
}

/**
 * 提取后端字段级校验详情为可读汇总文案；无详情时回退到服务端 message。
 */
export function formatApiError(error: unknown, fallback = '未知错误'): string {
  if (error instanceof ApiError) {
    if (Array.isArray(error.details)) {
      const reasons: string[] = []
      const seen = new Set<string>()
      for (const item of error.details) {
        if (!item || typeof item !== 'object') continue
        const detail = item as { reason?: unknown; message?: unknown }
        const text = typeof detail.reason === 'string'
          ? detail.reason.trim()
          : (typeof detail.message === 'string' ? detail.message.trim() : '')
        if (!text || seen.has(text)) continue
        seen.add(text)
        reasons.push(text)
      }
      if (reasons.length > 0) {
        return reasons.join('；')
      }
    }
    return error.message || fallback
  }
  return error instanceof Error && error.message ? error.message : fallback
}
