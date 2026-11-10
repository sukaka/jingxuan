import { describe, expect, it } from 'vitest'
import { isValidChineseIdCard, isValidMainlandMobile, normalizeChineseIdCard } from '../src/utils/channelValidation'

describe('合作渠道联系方式与身份证校验', () => {
  it('手机号只接受有效的 11 位中国大陆格式', () => {
    expect(isValidMainlandMobile('13800000000')).toBe(true)
    expect(isValidMainlandMobile('1380000000')).toBe(false)
    expect(isValidMainlandMobile('138000000000')).toBe(false)
    expect(isValidMainlandMobile('12800000000')).toBe(false)
    expect(isValidMainlandMobile('1380000000A')).toBe(false)
  })

  it('身份证必须为 18 位且出生日期和校验码正确', () => {
    expect(isValidChineseIdCard('11010519491231002X')).toBe(true)
    expect(isValidChineseIdCard('11010519491231002x')).toBe(true)
    expect(normalizeChineseIdCard('11010519491231002x')).toBe('11010519491231002X')
    expect(isValidChineseIdCard('12345678')).toBe(false)
    expect(isValidChineseIdCard('110105194912310021')).toBe(false)
    expect(isValidChineseIdCard('11010519490230002X')).toBe(false)
  })
})
