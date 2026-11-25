import { describe, expect, it } from 'vitest'
import { DEFAULT_PASSWORD_POLICY } from '../src/api/auth'
import {
  evaluatePasswordRules,
  evaluatePasswordStrength,
  validateChangePasswordInput,
} from '../src/utils/passwordPolicy'

describe('passwordPolicy 复杂度与一致性校验', () => {
  it('默认策略下校验全部复杂度维度并输出通过状态', () => {
    const rules = evaluatePasswordRules('Strong#2026!', DEFAULT_PASSWORD_POLICY, {
      username: 'manager1',
      currentPassword: 'OldPass@2025',
    })
    expect(rules.every((rule) => rule.passed)).toBe(true)
    expect(evaluatePasswordStrength('Strong#2026!', DEFAULT_PASSWORD_POLICY, {
      username: 'manager1',
      currentPassword: 'OldPass@2025',
    })).toMatchObject({ level: 'strong', label: '强' })
  })

  it('拦截长度不足、缺字符类别、含空白、含用户名、与原密码相同和两次输入不一致', () => {
    expect(validateChangePasswordInput(
      { currentPassword: '', newPassword: 'Strong#2026', confirmPassword: 'Strong#2026' },
      DEFAULT_PASSWORD_POLICY,
      'alice',
    )).toEqual({ valid: false, fieldErrors: { currentPassword: '请输入当前密码' } })

    expect(validateChangePasswordInput(
      { currentPassword: 'Old@12345', newPassword: 'Aa1!567', confirmPassword: 'Aa1!567' },
      DEFAULT_PASSWORD_POLICY,
      'alice',
    ).fieldErrors.newPassword).toContain('8-64')

    expect(validateChangePasswordInput(
      { currentPassword: 'Old@12345', newPassword: 'lowercase1!', confirmPassword: 'lowercase1!' },
      DEFAULT_PASSWORD_POLICY,
      'alice',
    ).fieldErrors.newPassword).toContain('大写字母')

    expect(validateChangePasswordInput(
      { currentPassword: 'Old@12345', newPassword: 'UPPERCASE1!', confirmPassword: 'UPPERCASE1!' },
      DEFAULT_PASSWORD_POLICY,
      'alice',
    ).fieldErrors.newPassword).toContain('小写字母')

    expect(validateChangePasswordInput(
      { currentPassword: 'Old@12345', newPassword: 'NoDigits!@#', confirmPassword: 'NoDigits!@#' },
      DEFAULT_PASSWORD_POLICY,
      'alice',
    ).fieldErrors.newPassword).toContain('数字')

    expect(validateChangePasswordInput(
      { currentPassword: 'Old@12345', newPassword: 'NoSpecial123', confirmPassword: 'NoSpecial123' },
      DEFAULT_PASSWORD_POLICY,
      'alice',
    ).fieldErrors.newPassword).toContain('特殊字符')

    expect(validateChangePasswordInput(
      { currentPassword: 'Old@12345', newPassword: 'Has Space1!', confirmPassword: 'Has Space1!' },
      DEFAULT_PASSWORD_POLICY,
      'alice',
    ).fieldErrors.newPassword).toContain('空白字符')

    expect(validateChangePasswordInput(
      { currentPassword: 'Old@12345', newPassword: 'MyAlice@2026', confirmPassword: 'MyAlice@2026' },
      DEFAULT_PASSWORD_POLICY,
      'alice',
    ).fieldErrors.newPassword).toContain('当前用户名')

    expect(validateChangePasswordInput(
      { currentPassword: 'Old@12345', newPassword: 'Old@12345', confirmPassword: 'Old@12345' },
      DEFAULT_PASSWORD_POLICY,
      'alice',
    ).fieldErrors.newPassword).toContain('不能与原密码相同')

    expect(validateChangePasswordInput(
      { currentPassword: 'Old@12345', newPassword: 'Strong#2026', confirmPassword: 'Strong#2027' },
      DEFAULT_PASSWORD_POLICY,
      'alice',
    )).toEqual({ valid: false, fieldErrors: { confirmPassword: '两次输入的新密码不一致' } })
  })

  it('未满足全部规则时强度评估为弱，满足 8-9 位基础规则时评估为中', () => {
    expect(evaluatePasswordStrength('', DEFAULT_PASSWORD_POLICY).level).toBe('empty')
    expect(evaluatePasswordStrength('weak1234', DEFAULT_PASSWORD_POLICY).level).toBe('weak')
    expect(evaluatePasswordStrength('Abcd123!', DEFAULT_PASSWORD_POLICY).level).toBe('medium')
  })
})
