import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  createSegmentOperationBatch,
  listCustomerSegments,
  listCustomerSegmentMembers,
  previewCustomerSegment,
} from '../src/api/customerSegment'
import { http } from '../src/api/http'
import { listProjects } from '../src/api/catalog'
import CustomerSegmentsView from '../src/views/CustomerSegmentsView.vue'
import { useAuthStore } from '../src/stores/auth'

vi.mock('../src/api/customerSegment', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../src/api/customerSegment')>()),
  listCustomerSegments: vi.fn(),
  listCustomerSegmentMembers: vi.fn(),
  previewCustomerSegment: vi.fn(),
  createCustomerSegment: vi.fn(),
  updateCustomerSegment: vi.fn(),
  deleteCustomerSegment: vi.fn(),
  createSegmentOperationBatch: vi.fn(),
}))
vi.mock('../src/api/catalog', () => ({ listProjects: vi.fn() }))
vi.mock('../src/api/dictionary', () => ({ getDictionaryOptions: vi.fn().mockResolvedValue([]) }))

const originalAdapter = http.defaults.adapter
const segment = {
  id: 7,
  name: '北京高等级已购客户',
  filters: {
    cityCodes: ['BJ'], tierCodes: ['A'], attentionProjectIds: [101],
    purchasedProjectIds: [101], redeemedProjectIds: [101], redeemedMinCount: 1,
  },
  version: 0,
  createdAt: '2026-11-01T00:00:00Z',
  updatedAt: '2026-11-01T00:00:00Z',
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(listCustomerSegments).mockResolvedValue({ items: [segment], total: 1, page: 1, size: 20 })
  vi.mocked(listCustomerSegmentMembers).mockResolvedValue({ items: [], total: 0, page: 1, size: 20 })
  vi.mocked(previewCustomerSegment).mockResolvedValue({ items: [], total: 0, page: 1, size: 20 })
  vi.mocked(listProjects).mockResolvedValue({
    items: [{ id: 101, projectCode: 'P101', projectName: '基础水光', categoryCode: null,
      listPriceFen: 100, status: 'ACTIVE', createdAt: '', version: 0 }],
    total: 1, page: 1, size: 100,
  })
})

afterEach(() => {
  http.defaults.adapter = originalAdapter
  document.body.innerHTML = ''
})

describe('CustomerSegmentsView 与 API', () => {
  it('分组创建/更新/删除/操作批次携带筛选、version 和幂等键', async () => {
    const calls: { url?: string; method?: string; data?: unknown; headers?: unknown }[] = []
    http.defaults.adapter = async (config) => {
      calls.push({ url: config.url, method: config.method,
        data: config.data ? JSON.parse(config.data as string) : undefined, headers: config.headers })
      return {
        status: 200, statusText: 'OK', headers: {}, config,
        data: { code: 'OK', message: 'success', data: segment, requestId: 'v33', timestamp: '' },
      }
    }

    const api = await vi.importActual<typeof import('../src/api/customerSegment')>('../src/api/customerSegment')
    await api.createCustomerSegment({ name: segment.name, filters: segment.filters }, 'segment-create')
    await api.previewCustomerSegment({ filters: segment.filters, page: 1, size: 20 })
    await api.updateCustomerSegment(7, { ...segment, name: '更新分组' })
    await api.createSegmentOperationBatch(7, { operationType: 'FOLLOWUP', version: 1 }, 'segment-batch')
    await api.deleteCustomerSegment(7, 1)

    expect(calls[0]).toMatchObject({ url: '/customer-segments', method: 'post' })
    expect((calls[0]?.headers as Record<string, unknown>)['Idempotency-Key']).toBe('segment-create')
    expect(calls[1]).toMatchObject({ url: '/customer-segments/preview', method: 'post' })
    expect(calls[2]).toMatchObject({ url: '/customer-segments/7', method: 'patch' })
    expect(calls[2]?.data).toMatchObject({ version: 0 })
    expect(calls[3]).toMatchObject({ url: '/customer-segments/7/operation-batches', method: 'post' })
    expect((calls[3]?.headers as Record<string, unknown>)['Idempotency-Key']).toBe('segment-batch')
    expect(calls[4]).toMatchObject({ url: '/customer-segments/7', method: 'delete' })
  })

  it('页面区分实时预览与操作批次，只有显式创建批次才调用快照接口', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    auth.user = {
      userId: 1, username: 'admin', displayName: '管理员', userType: 'EMPLOYEE',
      roles: ['ADMIN'], permissions: ['customer:read', 'customer:write', 'dictionary:read', 'product:read'],
    }
    const wrapper = mount(CustomerSegmentsView, {
      global: { plugins: [ElementPlus, pinia] }, attachTo: document.body,
    })
    await flushPromises()

    await wrapper.get('[data-testid="segment-preview-7"]').trigger('click')
    await flushPromises()
    expect(listCustomerSegmentMembers).toHaveBeenCalledWith(7, 1, 20)
    expect(createSegmentOperationBatch).not.toHaveBeenCalled()

    await wrapper.get('[data-testid="segment-batch-7"]').trigger('click')
    await flushPromises()
    expect(createSegmentOperationBatch).toHaveBeenCalledWith(7,
      { operationType: 'FOLLOWUP', version: 0 }, expect.any(String))
  })

  it('members API 使用分页参数', async () => {
    const api = await vi.importActual<typeof import('../src/api/customerSegment')>('../src/api/customerSegment')
    const calls: { url?: string; params?: unknown }[] = []
    http.defaults.adapter = async (config) => {
      calls.push({ url: config.url, params: config.params })
      return {
        status: 200, statusText: 'OK', headers: {}, config,
        data: { code: 'OK', message: 'success', data: { items: [], total: 0, page: 2, size: 50 },
          requestId: 'v33', timestamp: '' },
      }
    }
    await api.listCustomerSegmentMembers(7, 2, 50)
    expect(calls[0]).toEqual({ url: '/customer-segments/7/members', params: { page: 2, size: 50 } })
  })
})
