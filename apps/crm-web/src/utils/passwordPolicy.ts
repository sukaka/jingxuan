import type { ChangePasswordRequest, PasswordPolicy } from '../api/auth'

export interface PasswordRuleCheck {
  key: string
  label: string
  passed: boolean
}

export interface PasswordStrength {
  level: 'empty' | 'weak' | 'medium' | 'strong'
  label: string
  percent: number
}

export interface ChangePasswordFieldErrors {
  currentPassword?: string
  newPassword?: string
  confirmPassword?: string
}

export interface PasswordCheckContext {
  username?: string
  currentPassword?: string
}

const MIN_USERNAME_CHECK_LENGTH = 3

interface CharScan {
  hasUppercase: boolean
  hasLowercase: boolean
  hasDigit: boolean
  hasSpecial: boolean
  hasWhitespaceOrControl: boolean
}

function scanPassword(value: string): CharScan {
  let hasUppercase = false
  let hasLowercase = false
  let hasDigit = false
  let hasSpecial = false
  let hasWhitespaceOrControl = false

  for (let i = 0; i < value.length; i += 1) {
    const ch = value[i] ?? ''
    const code = value.charCodeAt(i)
    if (/\s/.test(ch) || code <= 0x1f || (code >= 0x7f && code <= 0x9f)) {
      hasWhitespaceOrControl = true
    } else if (code >= 0x41 && code <= 0x5a) {
      hasUppercase = true
    } else if (code >= 0x61 && code <= 0x7a) {
      hasLowercase = true
    } else if (code >= 0x30 && code <= 0x39) {
      hasDigit = true
    } else if (code >= 0x21 && code <= 0x7e) {
      hasSpecial = true
    }
  }

  return { hasUppercase, hasLowercase, hasDigit, hasSpecial, hasWhitespaceOrControl }
}

function containsUsername(password: string, username?: string): boolean {
  if (!username) return false
  const normalized = username.trim().toLowerCase()
  if (normalized.length < MIN_USERNAME_CHECK_LENGTH) return false
  return password.toLowerCase().includes(normalized)
}

export function evaluatePasswordRules(
  newPassword: string,
  policy: PasswordPolicy,
  context?: PasswordCheckContext,
): PasswordRuleCheck[] {
  const hasInput = newPassword.length > 0
  const scan = scanPassword(newPassword)
  const checks: PasswordRuleCheck[] = [
    {
      key: 'length',
      label: `长度 ${policy.minLength}–${policy.maxLength} 位`,
      passed: newPassword.length >= policy.minLength && newPassword.length <= policy.maxLength,
    },
  ]

  if (policy.forbidWhitespace) {
    checks.push({
      key: 'whitespace',
      label: '不含空格、空白或控制字符',
      passed: hasInput && !scan.hasWhitespaceOrControl,
    })
  }
  if (policy.requireUppercase) {
    checks.push({
      key: 'uppercase',
      label: '包含至少 1 个大写字母（A-Z）',
      passed: scan.hasUppercase,
    })
  }
  if (policy.requireLowercase) {
    checks.push({
      key: 'lowercase',
      label: '包含至少 1 个小写字母（a-z）',
      passed: scan.hasLowercase,
    })
  }
  if (policy.requireDigit) {
    checks.push({
      key: 'digit',
      label: '包含至少 1 个数字（0-9）',
      passed: scan.hasDigit,
    })
  }
  if (policy.requireSpecial) {
    checks.push({
      key: 'special',
      label: '包含至少 1 个特殊字符（如 @#$%&*!?）',
      passed: scan.hasSpecial,
    })
  }
  if (policy.forbidUsername && (context?.username?.trim().length ?? 0) >= MIN_USERNAME_CHECK_LENGTH) {
    checks.push({
      key: 'username',
      label: '不包含当前用户名',
      passed: hasInput && !containsUsername(newPassword, context?.username),
    })
  }
  if (policy.forbidSameAsCurrent) {
    const current = context?.currentPassword ?? ''
    checks.push({
      key: 'sameAsCurrent',
      label: '与原密码不同',
      passed: hasInput && (!current || newPassword !== current),
    })
  }

  return checks
}

export function evaluatePasswordStrength(
  newPassword: string,
  policy: PasswordPolicy,
  context?: PasswordCheckContext,
): PasswordStrength {
  if (!newPassword) {
    return { level: 'empty', label: '未输入', percent: 0 }
  }
  const rules = evaluatePasswordRules(newPassword, policy, context)
  const allPassed = rules.every((rule) => rule.passed)
  const scan = scanPassword(newPassword)
  const categories = [scan.hasUppercase, scan.hasLowercase, scan.hasDigit, scan.hasSpecial].filter(Boolean).length

  if (!allPassed) {
    const passedRatio = rules.length ? rules.filter((rule) => rule.passed).length / rules.length : 0
    return { level: 'weak', label: '弱', percent: Math.max(18, Math.round(passedRatio * 45)) }
  }
  if (newPassword.length >= Math.max(12, policy.minLength + 4) && categories >= 4) {
    return { level: 'strong', label: '强', percent: 100 }
  }
  if (newPassword.length >= Math.max(10, policy.minLength + 2) && categories >= 4) {
    return { level: 'strong', label: '强', percent: 90 }
  }
  return { level: 'medium', label: '中', percent: 68 }
}

export function validateChangePasswordInput(
  input: ChangePasswordRequest,
  policy: PasswordPolicy,
  username?: string,
): { valid: boolean; fieldErrors: ChangePasswordFieldErrors } {
  const fieldErrors: ChangePasswordFieldErrors = {}

  if (!input.currentPassword.trim()) {
    fieldErrors.currentPassword = '请输入当前密码'
  }

  if (!input.newPassword) {
    fieldErrors.newPassword = '请输入新密码'
  } else {
    const rules = evaluatePasswordRules(input.newPassword, policy, {
      username,
      currentPassword: input.currentPassword,
    })
    const firstFailed = rules.find((rule) => !rule.passed)
    if (firstFailed) {
      if (firstFailed.key === 'length') {
        fieldErrors.newPassword = `新密码长度须为 ${policy.minLength}-${policy.maxLength} 位`
      } else if (firstFailed.key === 'whitespace') {
        fieldErrors.newPassword = '新密码不能包含空白字符或控制字符'
      } else if (firstFailed.key === 'uppercase') {
        fieldErrors.newPassword = '新密码必须包含至少 1 个大写字母'
      } else if (firstFailed.key === 'lowercase') {
        fieldErrors.newPassword = '新密码必须包含至少 1 个小写字母'
      } else if (firstFailed.key === 'digit') {
        fieldErrors.newPassword = '新密码必须包含至少 1 个数字'
      } else if (firstFailed.key === 'special') {
        fieldErrors.newPassword = '新密码必须包含至少 1 个特殊字符'
      } else if (firstFailed.key === 'username') {
        fieldErrors.newPassword = '新密码不能包含当前用户名'
      } else if (firstFailed.key === 'sameAsCurrent') {
        fieldErrors.newPassword = '新密码不能与原密码相同'
      }
    }
  }

  if (!input.confirmPassword) {
    fieldErrors.confirmPassword = '请再次输入新密码'
  } else if (input.newPassword !== input.confirmPassword) {
    fieldErrors.confirmPassword = '两次输入的新密码不一致'
  }

  return {
    valid: !fieldErrors.currentPassword && !fieldErrors.newPassword && !fieldErrors.confirmPassword,
    fieldErrors,
  }
}
