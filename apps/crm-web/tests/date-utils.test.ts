import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  addDaysInShanghai,
  endOfTodayInShanghaiIso,
  shanghaiDateString,
  shanghaiDateTimeMinute,
  shanghaiTimeString,
  todayInShanghai,
} from '../src/utils/date'

afterEach(() => {
  vi.useRealTimers()
})

describe('date.ts Asia/Shanghai 日期处理', () => {
  it('北京时间 00:30（UTC 前一日 16:30Z）仍返回上海当日日期，不发生 UTC 跨天偏移', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-10-25T16:30:00Z'))

    expect(todayInShanghai()).toBe('2026-10-26')
    expect(addDaysInShanghai(30)).toBe('2026-11-25')
    expect(endOfTodayInShanghaiIso()).toBe('2026-10-26T15:59:59.999Z')
  })

  it('带时区 Instant 转 Asia/Shanghai，纯日期与无时区时间保持原样', () => {
    expect(shanghaiDateString('2026-10-25T16:30:00Z')).toBe('2026-10-26')
    expect(shanghaiTimeString('2026-10-25T01:05:00Z')).toBe('09:05')
    expect(shanghaiDateTimeMinute('2026-10-25T16:05:00Z')).toBe('2026-10-26 00:05')

    expect(shanghaiDateString('2026-10-23')).toBe('2026-10-23')
    expect(shanghaiDateString('2026-10-23T10:00:00')).toBe('2026-10-23')
    expect(shanghaiTimeString('2026-10-23T10:00:00')).toBe('10:00')
    expect(shanghaiDateTimeMinute('2026-10-23T10:00:00')).toBe('2026-10-23 10:00')
  })
})
