import { describe, expect, it, vi } from 'vitest'
import { fetchAllPages, mapWithConcurrency } from '../src/utils/asyncBatch'

describe('asyncBatch.ts 并发池与安全分页', () => {
  it('mapWithConcurrency 限制最大并发数并保持返回顺序', async () => {
    let active = 0
    let maxActive = 0
    const results = await mapWithConcurrency([1, 2, 3, 4, 5, 6, 7], 3, async (item) => {
      active += 1
      maxActive = Math.max(maxActive, active)
      await new Promise((resolve) => setTimeout(resolve, item % 2 === 0 ? 10 : 5))
      active -= 1
      if (item === 4) throw new Error('boom')
      return item * 10
    })

    expect(maxActive).toBeLessThanOrEqual(3)
    expect(results[0]).toEqual({ status: 'fulfilled', value: 10 })
    expect(results[3]).toMatchObject({ status: 'rejected' })
    expect(results[6]).toEqual({ status: 'fulfilled', value: 70 })
  })

  it('fetchAllPages 在 items.length < size 时立即停止，避免不一致 total 导致死循环', async () => {
    const fetchPage = vi.fn().mockResolvedValue({ items: [1, 2, 3], total: 9 })
    const result = await fetchAllPages(fetchPage, { size: 100, maxPages: 10 })

    expect(fetchPage).toHaveBeenCalledTimes(1)
    expect(result).toEqual({ items: [1, 2, 3], total: 9, truncated: false })
  })

  it('fetchAllPages 正常翻页并在超过 maxPages 时标记 truncated', async () => {
    const fetchPage = vi.fn(async (page: number, size: number) => ({
      items: Array.from({ length: size }, (_, i) => (page - 1) * size + i + 1),
      total: 10,
    }))
    const result = await fetchAllPages(fetchPage, { size: 3, maxPages: 2 })

    expect(fetchPage).toHaveBeenCalledTimes(2)
    expect(result.items).toEqual([1, 2, 3, 4, 5, 6])
    expect(result.total).toBe(10)
    expect(result.truncated).toBe(true)
  })
})
