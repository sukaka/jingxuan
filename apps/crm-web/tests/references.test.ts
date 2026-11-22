import { beforeEach, describe, expect, it, vi } from 'vitest'
import { listInstitutions, listProjects } from '../src/api/catalog'
import { getCustomer } from '../src/api/customer'
import { resetReferenceNames, useReferenceNames } from '../src/views/references'

vi.mock('../src/api/catalog', () => ({
  listInstitutions: vi.fn(),
  listProjects: vi.fn(),
}))

vi.mock('../src/api/customer', () => ({
  getCustomer: vi.fn(),
}))

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

describe('references.ts 缓存隔离与失败不中毒', () => {
  beforeEach(() => {
    resetReferenceNames()
    vi.clearAllMocks()
  })

  it('resetReferenceNames 清空所有映射并丢弃在途旧租户响应', async () => {
    const instDeferred = deferred<{ items: Array<{ id: number; shortName: string; fullName: string }>; total: number; page: number; size: number }>()
    const projDeferred = deferred<{ items: Array<{ id: number; projectCode: string; projectName: string }>; total: number; page: number; size: number }>()
    const custDeferred = deferred<{ id: number; customerNo: string; displayName: string }>()

    vi.mocked(listInstitutions).mockReturnValueOnce(instDeferred.promise as never)
    vi.mocked(listProjects).mockReturnValueOnce(projDeferred.promise as never)
    vi.mocked(getCustomer).mockReturnValueOnce(custDeferred.promise as never)

    const refs = useReferenceNames()
    const loadCustomerPromise = refs.loadCustomerNames([101])

    // 用户在请求尚未完成时登出/切换账号
    resetReferenceNames()

    instDeferred.resolve({ items: [{ id: 1, shortName: '旧机构', fullName: '旧机构全称' }], total: 1, page: 1, size: 100 })
    projDeferred.resolve({ items: [{ id: 2, projectCode: 'OLD', projectName: '旧项目' }], total: 1, page: 1, size: 100 })
    custDeferred.resolve({ id: 101, customerNo: 'C101', displayName: '旧客户' })
    await loadCustomerPromise
    await Promise.resolve()

    expect(refs.institutionLabel(1)).toBe('机构 #1')
    expect(refs.projectLabel(2)).toBe('项目 #2')
    expect(refs.projectCodeLabel('OLD')).toBe('OLD')
    expect(refs.customerLabel(101)).toBe('客户 #101')
  })

  it('loadReferences 部分失败时不锁死后续重试，loadCustomerNames 不缓存失败客户', async () => {
    vi.mocked(listInstitutions)
      .mockRejectedValueOnce(new Error('403 forbidden'))
      .mockResolvedValueOnce({ items: [{ id: 10, shortName: '新机构', fullName: '新机构全称' }], total: 1, page: 1, size: 100 } as never)
    vi.mocked(listProjects)
      .mockResolvedValue({ items: [{ id: 20, projectCode: 'PRJ-20', projectName: '水光项目' }], total: 1, page: 1, size: 100 } as never)
    vi.mocked(getCustomer)
      .mockRejectedValueOnce(new Error('403 forbidden'))
      .mockResolvedValueOnce({ id: 55, customerNo: 'C055', displayName: '李四' } as never)

    const first = useReferenceNames()
    await first.loadCustomerNames([55])
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(first.institutionLabel(10)).toBe('机构 #10')
    expect(first.customerLabel(55)).toBe('客户 #55')

    // 权限变更或下一用户重试时不会被永久缓存空/占位值
    const second = useReferenceNames()
    await second.loadCustomerNames([55])
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(listInstitutions).toHaveBeenCalledTimes(2)
    expect(getCustomer).toHaveBeenCalledTimes(2)
    expect(second.institutionLabel(10)).toBe('新机构')
    expect(second.customerLabel(55)).toBe('李四')
  })
})
