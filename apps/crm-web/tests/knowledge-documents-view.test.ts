import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuthUser } from '../src/api/auth'
import { useAuthStore } from '../src/stores/auth'
import type { KnowledgeDocumentItem } from '../src/api/knowledge'
import {
  disableKnowledgeDocument,
  listKnowledgeDocuments,
  publishKnowledgeDocument,
  retryKnowledgeSyncJob,
} from '../src/api/knowledge'
import { getMaterial, listMaterials } from '../src/api/material'
import KnowledgeDocumentsView from '../src/views/KnowledgeDocumentsView.vue'

vi.mock('../src/api/knowledge', () => ({
  listKnowledgeDocuments: vi.fn(),
  publishKnowledgeDocument: vi.fn(),
  retryKnowledgeSyncJob: vi.fn(),
  disableKnowledgeDocument: vi.fn(),
}))
vi.mock('../src/api/material', () => ({
  listMaterials: vi.fn(),
  getMaterial: vi.fn(),
}))

const documentItem: KnowledgeDocumentItem = {
  id: 101,
  materialId: 10,
  activeRevisionId: 20,
  datasetId: 'dataset-1',
  difyDocumentId: 'doc-1',
  status: 'ACTIVE',
  publishedAt: '2026-10-27T10:00:00Z',
  latestJobId: 501,
  latestJobStatus: 'FAILED',
  attemptCount: 3,
  lastErrorMasked: 'WORKER_UNAVAILABLE',
  version: 4,
}

const material = {
  id: 10,
  materialNo: 'MT-10',
  title: '合成服务流程',
  categoryCode: 'GUIDE',
  institutionId: 1,
  projectId: 30,
  cityCode: '310100',
  summary: '摘要',
  tags: [],
  currentRevisionId: 20,
  reviewStatus: 'APPROVED',
  offlineAt: null,
  offlineBy: null,
  offlineReason: null,
  createdBy: 1,
  createdAt: '2026-10-27T08:00:00Z',
  updatedAt: '2026-10-27T08:00:00Z',
  version: 2,
}

const revision = {
  id: 20,
  materialId: 10,
  revisionNo: 2,
  title: '合成服务流程',
  summary: '摘要',
  categoryCode: 'GUIDE',
  institutionId: 1,
  projectId: 30,
  cityCode: '310100',
  tags: [],
  fileId: 99,
  changeNote: null,
  status: 'APPROVED',
  reviewerId: 2,
  reviewNote: null,
  reviewedAt: '2026-10-27T09:00:00Z',
  createdBy: 1,
  createdAt: '2026-10-27T08:00:00Z',
  updatedAt: '2026-10-27T08:00:00Z',
  version: 2,
}

function mountView(permissions = ['ai:knowledge:read', 'ai:knowledge:publish', 'material:read']) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore(pinia)
  auth.user = {
    userId: 1, username: 'knowledge-test', displayName: '知识资料测试账号', userType: 'EMPLOYEE',
    roles: ['ADMIN'], permissions,
  } satisfies AuthUser
  auth.initialized = true
  return mount(KnowledgeDocumentsView, {
    global: { plugins: [pinia, ElementPlus] },
    attachTo: document.body,
  })
}

beforeEach(() => {
  vi.mocked(listKnowledgeDocuments).mockImplementation(async ({ page, size }) => ({ items: [documentItem], total: 41, page, size }))
  vi.mocked(listMaterials).mockResolvedValue({ items: [material], total: 1, page: 1, size: 100 })
  vi.mocked(getMaterial).mockResolvedValue({ material, revisions: [revision] })
  vi.mocked(publishKnowledgeDocument).mockResolvedValue({
    id: 501, documentId: 101, revisionId: 20, status: 'WAITING', contentHash: 'a'.repeat(64),
    attemptCount: 0, lastErrorMasked: null, version: 0,
  })
  vi.mocked(retryKnowledgeSyncJob).mockResolvedValue({
    id: 501, documentId: 101, revisionId: 20, status: 'PENDING', contentHash: 'a'.repeat(64),
    attemptCount: 0, lastErrorMasked: null, version: 4,
  })
  vi.mocked(disableKnowledgeDocument).mockResolvedValue({ ...documentItem, status: 'DISABLED' })
})

afterEach(() => {
  vi.clearAllMocks()
  document.body.innerHTML = ''
})

describe('KnowledgeDocumentsView 知识资料管理', () => {
  it('选择审核素材后发布指定修订和乐观锁版本', async () => {
    const wrapper = mountView()
    await flushPromises()

    const selects = wrapper.findAllComponents({ name: 'ElSelect' })
    await selects[0].vm.$emit('update:modelValue', 10)
    await selects[0].vm.$emit('change', 10)
    await flushPromises()
    await selects[1].vm.$emit('update:modelValue', 20)
    await wrapper.get('[data-testid="knowledge-publish"]').trigger('click')
    await flushPromises()

    expect(getMaterial).toHaveBeenCalledWith(10)
    expect(publishKnowledgeDocument).toHaveBeenCalledWith(
      { materialId: 10, revisionId: 20, version: 2 }, expect.any(String),
    )
    expect(listMaterials).toHaveBeenCalledWith(expect.objectContaining({ status: 'APPROVED' }))
    expect(document.body.textContent).toContain('发布任务已创建')
    wrapper.unmount()
  })

  it('失败任务可重试，资料可停用，且都使用真实 ID 和幂等键', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('知识库')
    expect(wrapper.get('[data-testid="knowledge-job-status-501"]').text()).toBe('失败')
    expect(wrapper.text()).toContain('任务 #501 · 已尝试 3 次')
    expect(wrapper.text()).not.toContain('FAILED')
    expect(wrapper.text()).toContain('WORKER_UNAVAILABLE')
    await wrapper.get('[data-testid="knowledge-retry-501"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="knowledge-disable-101"]').trigger('click')
    await flushPromises()

    expect(retryKnowledgeSyncJob).toHaveBeenCalledWith(501, expect.any(String))
    expect(disableKnowledgeDocument).toHaveBeenCalledWith(101, expect.any(String))
    expect(document.body.textContent).toContain('重试任务已恢复')
    expect(document.body.textContent).toContain('资料已停用')
    wrapper.unmount()
  })

  it('已停用资料不提供重试入口', async () => {
    vi.mocked(listKnowledgeDocuments).mockResolvedValue({
      items: [{ ...documentItem, status: 'DISABLED' }], total: 1, page: 1, size: 20,
    })
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('[data-testid="knowledge-retry-501"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="knowledge-disable-101"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('只读账号不展示发布、重试和停用操作，发布账号不越权读取素材', async () => {
    const readOnly = mountView(['ai:knowledge:read', 'material:read'])
    await flushPromises()
    expect(readOnly.find('[data-testid="knowledge-publish"]').exists()).toBe(false)
    expect(readOnly.find('[data-testid="knowledge-retry-501"]').exists()).toBe(false)
    expect(readOnly.find('[data-testid="knowledge-disable-101"]').exists()).toBe(false)
    readOnly.unmount()

    vi.clearAllMocks()
    vi.mocked(listKnowledgeDocuments).mockResolvedValue({ items: [documentItem], total: 1, page: 1, size: 20 })
    const publishOnly = mountView(['ai:knowledge:publish'])
    await flushPromises()
    expect(listKnowledgeDocuments).toHaveBeenCalledTimes(1)
    expect(listMaterials).not.toHaveBeenCalled()
    expect(publishOnly.text()).toContain('缺少素材查看权限')
    publishOnly.unmount()
  })

  it('资料列表支持服务端分页、状态筛选与恢复全部', async () => {
    const wrapper = mountView()
    await flushPromises()
    await wrapper.findComponent({ name: 'ElPagination' }).vm.$emit('current-change', 2)
    await flushPromises()
    expect(listKnowledgeDocuments).toHaveBeenLastCalledWith({ page: 2, size: 20, status: undefined })

    const status = wrapper.findAllComponents({ name: 'ElSelect' })
      .find((component) => component.attributes('data-testid') === 'knowledge-status-filter')!
    expect(status.attributes('data-testid')).toBe('knowledge-status-filter')
    await status.vm.$emit('update:modelValue', 'FAILED')
    await status.vm.$emit('change', 'FAILED')
    await flushPromises()
    expect(listKnowledgeDocuments).toHaveBeenLastCalledWith({ page: 1, size: 20, status: 'FAILED' })

    await status.vm.$emit('update:modelValue', '')
    await status.vm.$emit('change', '')
    await flushPromises()
    expect(listKnowledgeDocuments).toHaveBeenLastCalledWith({ page: 1, size: 20, status: undefined })
    await wrapper.get('[data-testid="knowledge-refresh"]').trigger('click')
    await flushPromises()
    expect(listKnowledgeDocuments).toHaveBeenLastCalledWith({ page: 1, size: 20, status: undefined })
    wrapper.unmount()
  })

  it('面向运营展示发布指引与状态颜色，不暴露内部锁版本', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('发布素材')
    expect(wrapper.text()).toContain('仅可发布审核通过的内容')
    expect(wrapper.text()).not.toContain('乐观锁')
    expect(wrapper.get('[data-testid="knowledge-document-status-101"]').classes()).toContain('success')
    expect(wrapper.get('[data-testid="knowledge-job-status-501"]').classes()).toContain('danger')
    expect(wrapper.get('[data-testid="knowledge-job-status-501"]').text()).toBe('失败')

    vi.mocked(listKnowledgeDocuments).mockResolvedValue({ items: [], total: 0, page: 1, size: 20 })
    await wrapper.get('[data-testid="knowledge-refresh"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('暂无已发布的知识资料')
    wrapper.unmount()
  })
})
