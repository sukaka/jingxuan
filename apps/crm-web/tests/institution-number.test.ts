import { describe, expect, it } from 'vitest'
import { decimalRateToPercent, fenToYuanExact, percentToDecimalRate, yuanToFenExact } from '../src/views/institution/number'

describe('机构金额与费率精确换算', () => {
  it('元字符串转整数分，不用浮点金额', () => {
    expect(yuanToFenExact('3311.00')).toBe(331100)
    expect(yuanToFenExact('0.01')).toBe(1)
    expect(yuanToFenExact('999999999999.99')).toBe(99999999999999)
    expect(yuanToFenExact('-1.23')).toBe(-123)
    expect(() => yuanToFenExact('0.001')).toThrow('最多支持 2 位小数')
    expect(() => yuanToFenExact('1e3')).toThrow('有效的十进制数字')
  })

  it('整数分格式化为元且保留两位', () => {
    expect(fenToYuanExact(331100)).toBe('3311.00')
    expect(fenToYuanExact(1)).toBe('0.01')
    expect(fenToYuanExact(-123)).toBe('-1.23')
    expect(fenToYuanExact(null)).toBe('-')
    expect(() => fenToYuanExact(1.1)).toThrow('安全整数')
  })

  it('百分比与 Decimal 文本精确互转', () => {
    expect(percentToDecimalRate('18')).toBe('0.180000')
    expect(percentToDecimalRate('18.25')).toBe('0.182500')
    expect(percentToDecimalRate('18.25%')).toBe('0.182500')
    expect(percentToDecimalRate('0.0001')).toBe('0.000001')
    expect(decimalRateToPercent('0.182500')).toBe('18.25')
    expect(decimalRateToPercent(0.182500)).toBe('18.25')
    expect(decimalRateToPercent('0.000001')).toBe('0.0001')
    expect(decimalRateToPercent(null)).toBe('-')
    expect(() => percentToDecimalRate('0.00001')).toThrow('最多支持 4 位小数')
    expect(() => decimalRateToPercent('0.0000001')).toThrow('最多支持 6 位小数')
  })
})
