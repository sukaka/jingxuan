import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent } from 'vue'
import type { AuthUser } from '../src/api/auth'
import type { FileMetadata } from '../src/api/file'
import { getFileMetadata } from '../src/api/file'
import type { Material, MaterialDetail } from '../src/api/material'
import {
  createMaterial,
  createMaterialRevision,
  getMaterial,
  getMaterialRevisionDownload,
  listMaterials,
  offlineMaterial,
  reviewMaterialRevision,
  submitMaterialRevision,
} from '../src/api/material'
import { useAuthStore } from '../src/stores/auth'
import MaterialsView from '../src/views/MaterialsView.vue'

vi.mock('../src/api/material', () => ({
  listMaterials: vi.fn(),
  getMaterial: vi.fn(),
  createMaterial: vi.fn(),
  updateMaterial: vi.fn(),
  createMaterialRevision: vi.fn(),
  submitMaterialRevision: vi.fn(),
  reviewMaterialRevision: vi.fn(),
  offlineMaterial: vi.fn(),
  getMaterialRevisionDownload: vi.fn(),
}))
vi.mock('../src/api/file', () => ({
  getFileMetadata: vi.fn(),
  sha256File: vi.fn(),
  initFileUpload: vi.fn(),
  putPresignedFile: vi.fn(),
  completeFileUpload: vi.fn(),
  waitForFileClean: vi.fn(),
}))
vi.mock('../src/api/catalog', () => ({
  listInstitutions: vi.fn().mockResolvedValue({
    items: [{ id: 91, shortName: '测试机构', fullName: '测试机构全称' }], total: 1, page: 1, size: 100,
  }),
  listProjects: vi.fn().mockResolvedValue({
    items: [{ id: 101, projectCode: 'PROJECT-TEST', projectName: '测试项目' }], total: 1, page: 1, size: 100,
  }),
}))
vi.mock('../src/api/dictionary', () => ({
  getDictionaryOptions: vi.fn().mockImplementation((code: string) => Promise.resolve(
    code === 'materialTypes'
      ? [{ code: 'CASE', label: '案例', sortNo: 1 }]
      : [{ code: 'SH', label: '上海', sortNo: 1 }],
  )),
}))

const FileUploadFieldStub = defineComponent({
  name: 'FileUploadField',
  props: {
    modelValue: { type: Number, default: undefined },
    institutionId: { type: Number, default: undefined },
    classification: { type: String, default: 'MATERIAL' },
    label: { type: String, default: '' },
  },
  emits: ['update:modelValue', 'uploaded'],
  template: '<div data-testid="material-file-upload" />',
})

const approved: Material = {
  id: 11,
  materialNo: 'MAT-TEST',
  title: '<img src=x onerror=alert(1)>授权案例',
  categoryCode: 'CASE',
  institutionId: 91,
  projectId: 101,
  cityCode: 'SH',
  summary: '仅用于自动化测试的素材摘要',
  tags: ['测试标签'],
  currentRevisionId: 102,
  reviewStatus: 'APPROVED',
  offlineAt: null,
  offlineBy: null,
  offlineReason: null,
  createdBy: 1,
  createdAt: '2026-10-24T01:00:00Z',
  updatedAt: '2026-10-25T01:00:00Z',
  version: 3,
}

function revision(id: number, status: string, version: number) {
  return {
    id,
    materialId: 11,
    revisionNo: id - 100,
    title: approved.title,
    summary: approved.summary,
    categoryCode: 'CASE',
    institutionId: 91,
    projectId: 101,
    cityCode: 'SH',
    tags: ['测试标签'],
    fileId: 701,
    changeNote: '测试版本说明',
    status,
    reviewerId: status === 'APPROVED' ? 2 : null,
    reviewNote: null,
    reviewedAt: status === 'APPROVED' ? '2026-10-25T02:00:00Z' : null,
    createdBy: 1,
    createdAt: '2026-10-25T01:00:00Z',
    updatedAt: '2026-10-25T01:00:00Z',
    version,
  }
}

const detail: MaterialDetail = {
  material: approved,
  revisions: [revision(103, 'PENDING', 1), revision(102, 'APPROVED', 2)],
}

const cleanMetadata: FileMetadata = {
  id: 701,
  objectKey: 'materials/test',
  originalName: 'material-test.pdf',
  mimeType: 'application/pdf',
  sizeBytes: 10,
  sha256: 'a'.repeat(64),
  classification: 'MATERIAL',
  institutionId: 91,
  scanStatus: 'CLEAN',
  uploadedBy: 1,
  etag: 'etag-test',
  lastError: null,
  expiresAt: null,
  createdAt: '2026-10-25T01:00:00Z',
  updatedAt: '2026-10-25T01:00:00Z',
  version: 1,
}

const wrappers: VueWrapper[] = []
afterEach(() => {
  while (wrappers.length) wrappers.pop()?.unmount()
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})

function mountView(permissions = ['material:read', 'material:write', 'material:review']) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore(pinia)
  auth.user = {
    userId: 1,
    username: 'material-test',
    displayName: '素材测试账号',
    userType: 'EMPLOYEE',
    roles: ['ADMIN'],
    permissions,
  } satisfies AuthUser
  auth.initialized = true
  const wrapper = mount(MaterialsView, {
    global: { plugins: [pinia, ElementPlus], stubs: { FileUploadField: FileUploadFieldStub } },
    attachTo: document.body,
  })
  wrappers.push(wrapper)
  return wrapper
}

function element(testId: string): HTMLElement {
  const hit = document.body.querySelector<HTMLElement>(`[data-testid="${testId}"]`)
  expect(hit, `缺少 ${testId}`).not.toBeNull()
  return hit!
}

async function click(testId: string) {
  element(testId).dispatchEvent(new MouseEvent('click', { bubbles: true }))
  await flushPromises()
}

async function input(testId: string, value: string) {
  const root = element(testId)
  const field = root.matches('input,textarea')
    ? root as HTMLInputElement | HTMLTextAreaElement
    : root.querySelector<HTMLInputElement | HTMLTextAreaElement>('input,textarea')
  expect(field, `缺少输入框 ${testId}`).not.toBeNull()
  field!.value = value
  field!.dispatchEvent(new Event('input', { bubbles: true }))
  await flushPromises()
}

describe('MaterialsView 真实版本、审核与预览', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(listMaterials).mockResolvedValue({ items: [approved], total: 1, page: 1, size: 20 })
    vi.mocked(getMaterial).mockResolvedValue(detail)
    vi.mocked(getFileMetadata).mockResolvedValue(cleanMetadata)
    vi.mocked(getMaterialRevisionDownload).mockResolvedValue({
      fileId: 701,
      downloadUrl: 'https://storage.example.test/material-signed',
      expiresAt: '2026-10-25T03:00:00Z',
    })
  })

  it('只展示接口数据与中文状态，标题按纯文本渲染且未审核版本不提供普通预览', async () => {
    const wrapper = mountView(['material:read'])
    await flushPromises()

    expect(wrapper.text()).toContain('MAT-TEST')
    expect(wrapper.text()).toContain('已审核')
    expect(wrapper.find('img[src="x"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('<img src=x onerror=alert(1)>授权案例')

    await click('material-detail-11')
    expect(document.body.textContent).toContain('待审核')
    expect(document.body.textContent).toContain('未审核版本不可作为对客素材')
    expect(element('revision-preview-102')).toBeTruthy()
    expect(document.body.querySelector('[data-testid="revision-preview-103"]')).toBeNull()
  })

  it('按原型使用七列单表格，显示真实机构项目名称并直接预览已审版本', async () => {
    const open = vi.spyOn(window, 'open').mockReturnValue(null)
    const wrapper = mountView(['material:read', 'material:write'])
    await flushPromises()

    for (const column of ['类型', '标题', '城市', '关联机构', '项目', '状态', '操作']) {
      expect(wrapper.text()).toContain(column)
    }
    expect(wrapper.text()).toContain('测试机构')
    expect(wrapper.text()).toContain('测试项目')
    expect(wrapper.find('.material-card').exists()).toBe(false)
    expect(wrapper.find('[data-testid="material-edit-11"]').exists()).toBe(true)
    expect(element('material-preview-11').textContent).toBe('预览')
    expect(element('material-edit-11').textContent).toBe('修改')
    expect(element('material-preview-11').classList).not.toContain('el-button--primary')
    expect(element('material-edit-11').classList).not.toContain('el-button--primary')

    await click('material-preview-11')
    expect(getFileMetadata).toHaveBeenCalledWith(701)
    expect(getMaterialRevisionDownload).toHaveBeenCalledWith(11, 102)
    expect(open).toHaveBeenCalledWith('https://storage.example.test/material-signed', '_blank', 'noopener,noreferrer')
  })

  it('下架填写原因并携带主档版本与幂等键，管理侧保留历史私有预览', async () => {
    const offline: Material = {
      ...approved,
      offlineAt: '2026-10-28T02:00:00Z',
      offlineBy: 2,
      offlineReason: '授权到期，停止对客使用',
      version: 4,
    }
    vi.mocked(listMaterials)
      .mockResolvedValueOnce({ items: [approved], total: 1, page: 1, size: 20 })
      .mockResolvedValueOnce({ items: [offline], total: 1, page: 1, size: 20 })
    vi.mocked(getMaterial).mockResolvedValue({ material: offline, revisions: detail.revisions })
    vi.mocked(offlineMaterial).mockResolvedValue(offline)
    vi.spyOn(ElMessageBox, 'prompt').mockResolvedValue({ value: '授权到期，停止对客使用' } as never)
    const open = vi.spyOn(window, 'open').mockReturnValue(null)
    const wrapper = mountView(['material:read', 'material:write', 'material:review'])
    await flushPromises()

    await click('material-offline-11')

    expect(ElMessageBox.prompt).toHaveBeenCalled()
    expect(offlineMaterial).toHaveBeenCalledWith(11, {
      version: 3,
      reason: '授权到期，停止对客使用',
    }, expect.any(String))
    expect(listMaterials).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('已下架')
    expect(document.body.querySelector('[data-testid="material-edit-11"]')).toBeNull()
    expect(document.body.querySelector('[data-testid="material-offline-11"]')).toBeNull()

    await click('material-detail-11')
    expect(document.body.textContent).toContain('授权到期，停止对客使用')
    expect(document.body.textContent).toContain('已下架素材已冻结修改、送审与审核')
    expect(document.body.querySelector('[data-testid="material-new-revision"]')).toBeNull()
    expect(document.body.querySelector('[data-testid="revision-review-approve-103"]')).toBeNull()
    expect(element('revision-preview-102')).toBeTruthy()

    await click('revision-preview-102')
    expect(getMaterialRevisionDownload).toHaveBeenCalledWith(11, 102)
    expect(open).toHaveBeenCalledWith('https://storage.example.test/material-signed', '_blank', 'noopener,noreferrer')
  })

  it('新建素材必须一次提交 CLEAN 文件与首个版本，不先创建空壳或客户端编号', async () => {
    vi.mocked(createMaterial).mockResolvedValue(approved)
    const wrapper = mountView(['material:read', 'material:write'])
    await flushPromises()
    await click('material-create')

    await input('revision-title', '真实资料首版')
    await input('revision-change-note', '核对来源后创建首个版本')
    await click('revision-save')
    expect(createMaterial).not.toHaveBeenCalled()

    const upload = wrapper.getComponent({ name: 'FileUploadField' })
    upload.vm.$emit('update:modelValue', 701)
    upload.vm.$emit('uploaded', cleanMetadata)
    await flushPromises()
    await click('revision-save')

    expect(createMaterial).toHaveBeenCalledWith(expect.objectContaining({
      title: '真实资料首版', categoryCode: 'CASE', fileId: 701, changeNote: '核对来源后创建首个版本',
    }), expect.any(String))
    expect(vi.mocked(createMaterial).mock.calls[0]?.[0]).not.toHaveProperty('materialNo')
    expect(createMaterialRevision).not.toHaveBeenCalled()
  })

  it('追加版本必须收到 CLEAN 上传结果并携带素材 version', async () => {
    const editableDetail = { material: approved, revisions: [revision(103, 'APPROVED', 1)] }
    vi.mocked(getMaterial).mockResolvedValue(editableDetail)
    vi.mocked(createMaterialRevision).mockResolvedValue({ ...approved, revisions: editableDetail.revisions })
    const wrapper = mountView(['material:read', 'material:write'])
    await flushPromises()
    await click('material-detail-11')
    await click('material-new-revision')

    await input('revision-change-note', '追加经过授权核对的新版本')
    await click('revision-save')
    expect(createMaterialRevision).not.toHaveBeenCalled()

    const upload = wrapper.getComponent({ name: 'FileUploadField' })
    expect(upload.props('classification')).toBe('MATERIAL')
    expect(upload.props('institutionId')).toBe(91)
    upload.vm.$emit('update:modelValue', 701)
    upload.vm.$emit('uploaded', cleanMetadata)
    await flushPromises()
    await click('revision-save')

    expect(createMaterialRevision).toHaveBeenCalledWith(11, expect.objectContaining({
      version: 3,
      fileId: 701,
      changeNote: '追加经过授权核对的新版本',
      title: approved.title,
    }), expect.any(String))
  })

  it('驳回必须填写审核说明；审核后重新读取详情，私有预览使用服务端短链', async () => {
    const open = vi.spyOn(window, 'open').mockReturnValue(null)
    vi.mocked(reviewMaterialRevision).mockResolvedValue({ ...approved, reviewStatus: 'REJECTED' })
    mountView()
    await flushPromises()
    await click('material-detail-11')
    await click('revision-review-reject-103')
    await click('revision-review-submit')
    expect(reviewMaterialRevision).not.toHaveBeenCalled()

    await input('revision-review-note', '授权证明不足，请新建版本补充')
    vi.mocked(getMaterial).mockResolvedValue({
      material: { ...approved, reviewStatus: 'REJECTED', currentRevisionId: null },
      revisions: [revision(103, 'REJECTED', 2), revision(102, 'APPROVED', 2)],
    })
    await click('revision-review-submit')
    expect(reviewMaterialRevision).toHaveBeenCalledWith(11, 103, {
      version: 1,
      decision: 'REJECTED',
      reviewNote: '授权证明不足，请新建版本补充',
    }, expect.any(String))
    expect(getMaterial).toHaveBeenCalledTimes(2)
    expect(getMaterialRevisionDownload).not.toHaveBeenCalled()

    await click('revision-preview-102')
    expect(getFileMetadata).toHaveBeenCalledWith(701)
    expect(getMaterialRevisionDownload).toHaveBeenCalledWith(11, 102)
    expect(open).toHaveBeenCalledWith('https://storage.example.test/material-signed', '_blank', 'noopener,noreferrer')
  })

  it('草稿送审需确认并携带修订 version，成功后再读取服务端状态', async () => {
    const draftMaterial = { ...approved, reviewStatus: 'DRAFT', currentRevisionId: null }
    vi.mocked(getMaterial).mockResolvedValue({ material: draftMaterial, revisions: [revision(104, 'DRAFT', 0)] })
    vi.mocked(submitMaterialRevision).mockResolvedValue({ ...draftMaterial, reviewStatus: 'PENDING' })
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    mountView(['material:read', 'material:write'])
    await flushPromises()

    await click('material-detail-11')
    await click('revision-submit-104')

    expect(ElMessageBox.confirm).toHaveBeenCalled()
    expect(submitMaterialRevision).toHaveBeenCalledWith(11, 104, 0, expect.any(String))
    expect(getMaterial).toHaveBeenCalledTimes(2)
  })

  it('没有任何素材权限时展示空态且不请求列表', async () => {
    mountView([])
    await flushPromises()

    expect(element('material-permission-empty').textContent).toContain('没有素材查看权限')
    expect(listMaterials).not.toHaveBeenCalled()
  })
})
