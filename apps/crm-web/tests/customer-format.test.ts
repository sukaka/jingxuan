import { describe, expect, it } from 'vitest'
import { customerTagLabel, fenToYuan, levelLabel, yuanToFen } from '../src/views/customer/shared'

describe('金额分↔元边界转换(整数安全)', () => {
  it('分转元保留两位小数', () => {
    expect(fenToYuan(128800)).toBe('1288.00')
    expect(fenToYuan(5)).toBe('0.05')
    expect(fenToYuan(0)).toBe('0.00')
  })

  it('元转分四舍五入为整数分,无浮点漂移', () => {
    expect(yuanToFen(1288)).toBe(128800)
    expect(yuanToFen(0.1 + 0.2)).toBe(30)
    expect(yuanToFen(12.3)).toBe(1230)
  })

  it('空值展示为 -', () => {
    expect(fenToYuan(null)).toBe('-')
    expect(fenToYuan(undefined)).toBe('-')
  })
})

describe('客户级别中文展示', () => {
  it('已知级别显示中文，未知级别不直接展示英文编码', () => {
    expect(levelLabel('HIGH')).toBe('高')
    expect(levelLabel('MEDIUM')).toBe('中')
    expect(levelLabel('LOW')).toBe('低')
    expect(levelLabel('VERY_HIGH')).toBe('未知')
  })
})

describe('客户标签中文展示', () => {
  it('内部标签编码显示中文，未知英文编码不直接回显', () => {
    expect(customerTagLabel('VIP')).toBe('贵宾客户')
    expect(customerTagLabel('HIGH_INTENT')).toBe('高意向')
    expect(customerTagLabel('NEW_7D')).toBe('近 7 天新增')
    expect(customerTagLabel('REFERRAL_GOOD')).toBe('优质转介绍')
    expect(customerTagLabel('SOME_ENGLISH_TAG')).toBe('其他标签')
    expect(customerTagLabel('自定义中文标签')).toBe('自定义中文标签')
  })
})
