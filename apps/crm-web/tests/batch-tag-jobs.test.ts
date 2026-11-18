import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  createBatchTagJob,
  executeBatchTagJob,
  previewBatchTagJob,
  retryBatchTagJob,
  rollbackBatchTagJob,
} from '../src/api/batchTagJob'
import type { BatchTagJobDetail, BatchTagPreview } from '../src/api/batchTagJob'
import { http } from '../src/api/http'
import { listProjects } from '../src/api/catalog'
import { listCustomerSegments } from '../src/api/customerSegment'
import BatchTagJobsView from '../src/views/BatchTagJobsView.vue'
import { useAuthStore } from '../src/stores/auth'

vi.mock('../src/api/batchTagJob', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../src/api/batchTagJob')>()),
  previewBatchTagJob: vi.fn(),
  createBatchTagJob: vi.fn(),
  listBatchTagJobs: vi.fn(),
  getBatchTagJob: vi.fn(),
  executeBatchTagJob: vi.fn(),
  retryBatchTagJob: vi.fn(),
  rollbackBatchTagJob: vi.fn(),
  createBatchTagOperationBatch: vi.fn(),
}))
vi.mock('../src/api/catalog', () => ({ listProjects: vi.fn() }))
vi.mock('../src/api/customerSegment', () => ({ listCustomerSegments: vi.fn() }))
vi.mock('../src/api/dictionary', () => ({ getDictionaryOptions: vi.fn().mockResolvedValue([
  { code: 'VIP', label: '贵宾客户', sortNo: 1 },
]) }))

const members = [
  { customerId: 11, customerNo: 'C0000000011', customerName: '合成客户一', ownerStaffId: 2, teamId: 1 },
  { customerId: 12, customerNo: 'C0000000012', customerName: '合成客户二', ownerStaffId: 2, teamId: 1 },
]
const preview: BatchTagPreview = {
  previewId: 'BP-V34', operation: 'ADD', tagCodes: ['VIP'], sourceType: 'FILTER', sourceId: null,
  items: members, total: 2, page: 1, size: 20,
}
const pending: BatchTagJobDetail = {
  job: {
    id: 34, jobNo: 'BT-V34', requestedBy: 1, operation: 'ADD', tagCodes: ['VIP'],
    sourceType: 'FILTER', sourceId: null, sourceSnapshot: { filters: {} },
    scopeSnapshot: { type: 'ALL', teamIds: [], selfId: 1 }, idempotencyKey: 'create-v34',
    rollbackOfJobId: null, status: 'PENDING', totalCount: 2, succeededCount: 0, noChangeCount: 0,
    skippedCount: 0, failedCount: 0, attemptCount: 0, lastErrorMasked: null,
    createdAt: '2026-11-12T00:00:00Z', startedAt: null, finishedAt: null, version: 0,
  },
  items: {
    items: members.map((member, index) => ({
      id: 340 + index, customerId: member.customerId, customerNo: member.customerNo,
      customerName: member.customerName, ownerStaffId: 2, teamId: 1, tagCodes: ['VIP'],
      status: 'PENDING', failureCode: null, reason: null, beforeTagCodes: null,
      afterTagCodes: null, changedTagCodes: null, attemptCount: 0, processedAt: null, version: 0,
    })),
    total: 2, page: 1, size: 100,
  },
}
const partial: BatchTagJobDetail = {
  ...pending,
  job: { ...pending.job, status: 'PARTIAL_FAILED', succeededCount: 1, failedCount: 1, attemptCount: 1, version: 2 },
  items: {
    ...pending.items,
    items: [
      { ...pending.items.items[0], status: 'SUCCEEDED', beforeTagCodes: [], afterTagCodes: ['VIP'],
        changedTagCodes: ['VIP'], attemptCount: 1 },
      { ...pending.items.items[1], status: 'FAILED', failureCode: 'TAG_DICTIONARY_INACTIVE',
        reason: '标签不存在或字典已停用，恢复有效字典后可重试失败项', attemptCount: 1 },
    ],
  },
}
const succeeded: BatchTagJobDetail = {
  ...partial,
  job: { ...partial.job, status: 'SUCCEEDED', succeededCount: 2, failedCount: 0, attemptCount: 2, version: 3 },
}

const originalAdapter = http.defaults.adapter

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(previewBatchTagJob).mockResolvedValue(preview)
  vi.mocked(createBatchTagJob).mockResolvedValue(pending)
  vi.mocked(executeBatchTagJob).mockResolvedValue(partial)
  vi.mocked(retryBatchTagJob).mockResolvedValue(succeeded)
  vi.mocked(rollbackBatchTagJob).mockResolvedValue({
    ...pending,
    job: { ...pending.job, id: 35, operation: 'REMOVE', rollbackOfJobId: 34 },
  })
  vi.mocked(listProjects).mockResolvedValue({ items: [], total: 0, page: 1, size: 100 })
  vi.mocked(listCustomerSegments).mockResolvedValue({ items: [], total: 0, page: 1, size: 100 })
})

afterEach(() => {
  http.defaults.adapter = originalAdapter
  document.body.innerHTML = ''
})

function mountView() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = {
    userId: 1, username: 'admin', displayName: '管理员', userType: 'EMPLOYEE',
    roles: ['ADMIN'], permissions: ['customer:read', 'customer:write', 'dictionary:read', 'product:read'],
  }
  return mount(BatchTagJobsView, { global: { plugins: [ElementPlus, pinia] }, attachTo: document.body })
}

describe('批量重打标 API', () => {
  it('预览、创建、执行、重试和独立回滚携带来源、version 与幂等键', async () => {
    const calls: { url?: string; method?: string; data?: unknown; headers?: unknown; params?: unknown }[] = []
    http.defaults.adapter = async (config) => {
      calls.push({ url: config.url, method: config.method,
        data: config.data ? JSON.parse(config.data as string) : undefined,
        headers: config.headers, params: config.params })
      return {
        status: 200, statusText: 'OK', headers: {}, config,
        data: { code: 'OK', message: 'success', data: pending, requestId: 'v34', timestamp: '' },
      }
    }
    const api = await vi.importActual<typeof import('../src/api/batchTagJob')>('../src/api/batchTagJob')
    const input = {
      operation: 'ADD' as const, tagCodes: ['VIP'],
      source: { type: 'FILTER' as const, filters: { cityCodes: ['BJ'] } }, page: 1, size: 20,
    }
    await api.previewBatchTagJob(input, 'preview-key')
    await api.createBatchTagJob('BP-V34', 'create-key')
    await api.executeBatchTagJob(34, 0, 'execute-key')
    await api.retryBatchTagJob(34, 2, 'retry-key')
    await api.rollbackBatchTagJob(34, 3, 'rollback-key')
    await api.createBatchTagOperationBatch(7, 4, 'snapshot-key')
    await api.listBatchTagJobs({ status: 'FAILED', page: 2, size: 50 })
    await api.getBatchTagJob(34, 2, 50)

    expect(calls[0]).toMatchObject({ url: '/batch-tag-jobs/preview', method: 'post', data: input })
    expect((calls[0]?.headers as Record<string, unknown>)['Idempotency-Key']).toBe('preview-key')
    expect(calls[1]).toMatchObject({ url: '/batch-tag-jobs', method: 'post', data: { previewId: 'BP-V34' } })
    expect(calls[2]).toMatchObject({ url: '/batch-tag-jobs/34/execute', data: { version: 0 } })
    expect(calls[3]).toMatchObject({ url: '/batch-tag-jobs/34/retry', data: { version: 2 } })
    expect(calls[4]).toMatchObject({ url: '/batch-tag-jobs/34/rollback', data: { version: 3 } })
    expect(calls[5]).toMatchObject({ url: '/customer-segments/7/operation-batches', method: 'post',
      data: { operationType: 'FOLLOWUP', version: 4 } })
    expect(calls[6]).toMatchObject({ url: '/batch-tag-jobs', method: 'get',
      params: { status: 'FAILED', page: 2, size: 50 } })
    expect(calls[7]).toMatchObject({ url: '/batch-tag-jobs/34', method: 'get',
      params: { itemPage: 2, itemSize: 50 } })
  })
})

describe('BatchTagJobsView', () => {
  it('严格按预览创建执行，展示进度逐项失败原因并仅重试失败项', async () => {
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.get('[data-testid="create-tag-job"]').attributes('disabled')).toBeDefined()

    const tagSelect = wrapper.getComponent('[data-testid="tag-select"]') as unknown as {
      vm: { $emit: (event: string, value: string[]) => void }
    }
    tagSelect.vm.$emit('update:modelValue', ['VIP'])
    await wrapper.get('[data-testid="preview-tag-job"]').trigger('click')
    await flushPromises()
    expect(previewBatchTagJob).toHaveBeenCalledWith(expect.objectContaining({
      operation: 'ADD', tagCodes: ['VIP'], source: expect.objectContaining({ type: 'FILTER' }),
    }), expect.any(String))
    expect(wrapper.text()).toContain('固定预览成员 2 人')

    await wrapper.get('[data-testid="create-tag-job"]').trigger('click')
    await flushPromises()
    expect(createBatchTagJob).toHaveBeenCalledWith('BP-V34', expect.any(String))
    await wrapper.get('[data-testid="execute-tag-job"]').trigger('click')
    await flushPromises()
    expect(executeBatchTagJob).toHaveBeenCalledWith(34, 0, expect.any(String))
    expect(wrapper.text()).toContain('PARTIAL_FAILED')
    expect(wrapper.text()).toContain('TAG_DICTIONARY_INACTIVE')
    expect(wrapper.text()).toContain('恢复有效字典后可重试失败项')
    expect(wrapper.text()).toContain('2 / 2（100%）')

    await wrapper.get('[data-testid="retry-tag-job"]').trigger('click')
    await flushPromises()
    expect(retryBatchTagJob).toHaveBeenCalledWith(34, 2, expect.any(String))
    expect(wrapper.text()).toContain('SUCCEEDED')
  })

  it('从已完成 REMOVE 任务创建独立反向任务而不改原任务', async () => {
    const completedRemove: BatchTagJobDetail = {
      ...succeeded,
      job: { ...succeeded.job, operation: 'REMOVE', status: 'SUCCEEDED', version: 2 },
    }
    const api = await import('../src/api/batchTagJob')
    vi.mocked(api.listBatchTagJobs).mockResolvedValue({ items: [completedRemove.job], total: 1, page: 1, size: 20 })
    vi.mocked(api.getBatchTagJob).mockResolvedValue(completedRemove)
    vi.mocked(rollbackBatchTagJob).mockResolvedValue({
      ...pending,
      job: { ...pending.job, id: 35, operation: 'ADD', rollbackOfJobId: 34, status: 'PENDING', version: 0 },
    })
    const wrapper = mountView()
    await flushPromises()
    await wrapper.get('[data-testid="tag-job-detail-34"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="rollback-tag-job"]').trigger('click')
    await flushPromises()
    expect(rollbackBatchTagJob).toHaveBeenCalledWith(34, 2, expect.any(String))
    expect(wrapper.text()).toContain('独立回滚')
    expect(wrapper.text()).toContain('rollbackOfJobId')
  })
})
