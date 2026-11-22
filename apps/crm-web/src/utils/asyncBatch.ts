export interface PageLike<T> {
  items: T[]
  total: number
}

export async function mapWithConcurrency<T, R>(
  items: readonly T[],
  limit: number,
  worker: (item: T, index: number) => Promise<R>,
): Promise<PromiseSettledResult<R>[]> {
  if (!items.length) return []
  const concurrency = Math.max(1, Math.min(limit, items.length))
  const results = new Array<PromiseSettledResult<R>>(items.length)
  let nextIndex = 0

  async function runWorker(): Promise<void> {
    while (nextIndex < items.length) {
      const current = nextIndex
      nextIndex += 1
      try {
        const value = await worker(items[current], current)
        results[current] = { status: 'fulfilled', value }
      } catch (reason) {
        results[current] = { status: 'rejected', reason }
      }
    }
  }

  await Promise.all(Array.from({ length: concurrency }, () => runWorker()))
  return results
}

export async function fetchAllPages<T>(
  fetchPage: (page: number, size: number) => Promise<PageLike<T>>,
  options?: { size?: number; maxPages?: number },
): Promise<{ items: T[]; total: number; truncated: boolean }> {
  const size = Math.min(100, Math.max(1, options?.size ?? 100))
  const maxPages = Math.max(1, options?.maxPages ?? 20)
  const items: T[] = []
  let total = 0
  let page = 1
  let truncated = false

  while (page <= maxPages) {
    const result = await fetchPage(page, size)
    const pageItems = result?.items ?? []
    items.push(...pageItems)
    total = typeof result?.total === 'number' ? result.total : items.length
    if (pageItems.length < size || page * size >= total) {
      break
    }
    if (page >= maxPages) {
      truncated = total > items.length
      break
    }
    page += 1
  }

  return { items, total, truncated }
}
