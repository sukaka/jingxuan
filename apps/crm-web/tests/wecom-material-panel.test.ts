import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getFileMetadata } from '../src/api/file'
import type { FileMetadata } from '../src/api/file'
import { getMaterialRevisionDownload, listMaterials } from '../src/api/material'
import type { Material } from '../src/api/material'
import { uploadWecomMaterialMedia } from '../src/api/wecomSidebar'
import { sendFileToCurrentChat, sendImageToCurrentChat } from '../src/services/wecomSdk'
import { ApiError } from '../src/types/api'
import WecomMaterialPanel from '../src/components/WecomMaterialPanel.vue'

vi.mock('../src/api/material', () => ({
  listMaterials: vi.fn(),
  getMaterialRevisionDownload: vi.fn(),
}))
vi.mock('../src/api/file', () => ({
  getFileMetadata: vi.fn(),
}))
vi.mock('../src/api/wecomSidebar', () => ({
  uploadWecomMaterialMedia: vi.fn(),
}))
vi.mock('../src/services/wecomSdk', () => ({
  sendImageToCurrentChat: vi.fn(),
  sendFileToCurrentChat: vi.fn(),
}))

let grantedPermissions = new Set(['wecom:sidebar:view', 'material:read'])
vi.mock('../src/stores/auth', () => ({
  useAuthStore: () => ({
    hasPermission: (code: string) => grantedPermissions.has(code),
  }),
}))

const imageMaterial: Material = {
  id: 21,
  materialNo: 'MT-IMG-001',
  title: '光子嫩肤术后对比图',
  categoryCode: 'CASE',
  institutionId: 91,
  projectId: 101,
  cityCode: 'SH',
  summary: '已获客户授权的合规案例对比图',
  tags: ['授权案例', '光电'],
  currentRevisionId: 201,
  reviewStatus: 'APPROVED',
  offlineAt: null,
  offlineBy: null,
  offlineReason: null,
  createdBy: 1,
  createdAt: '2026-11-10T01:00:00Z',
  updatedAt: '2026-11-12T08:00:00Z',
  version: 2,
  revisions: [
    {
      id: 201, materialId: 21, revisionNo: 1, title: '光子嫩肤术后对比图', summary: '已获客户授权的合规案例对比图',
      categoryCode: 'CASE', institutionId: 91, projectId: 101, cityCode: 'SH', tags: ['授权案例', '光电'],
      fileId: 801, changeNote: '首版审核通过', status: 'APPROVED', reviewerId: 2, reviewNote: null,
      reviewedAt: '2026-11-12T08:00:00Z', createdBy: 1, createdAt: '2026-11-10T01:00:00Z', updatedAt: '2026-11-12T08:00:00Z', version: 1,
    },
  ],
}

const pdfMaterial: Material = {
  id: 22,
  materialNo: 'MT-PDF-002',
  title: '院区到访指南手册',
  categoryCode: 'GUIDE',
  institutionId: null,
  projectId: null,
  cityCode: null,
  summary: '包含停车与术前注意事项的 PDF 指南',
  tags: ['到院指南'],
  currentRevisionId: 202,
  reviewStatus: 'APPROVED',
  offlineAt: null,
  offlineBy: null,
  offlineReason: null,
  createdBy: 1,
  createdAt: '2026-11-11T01:00:00Z',
  updatedAt: '2026-11-13T08:00:00Z',
  version: 1,
  revisions: [
    {
      id: 202, materialId: 22, revisionNo: 1, title: '院区到访指南手册', summary: '包含停车与术前注意事项的 PDF 指南',
      categoryCode: 'GUIDE', institutionId: null, projectId: null, cityCode: null, tags: ['到院指南'],
      fileId: 802, changeNote: '首版审核通过', status: 'APPROVED', reviewerId: 2, reviewNote: null,
      reviewedAt: '2026-11-13T08:00:00Z', createdBy: 1, createdAt: '2026-11-11T01:00:00Z', updatedAt: '2026-11-13T08:00:00Z', version: 1,
    },
  ],
}

const offlineMaterial: Material = {
  ...pdfMaterial,
  id: 23,
  materialNo: 'MT-OFF-003',
  title: '已下架资料',
  offlineAt: '2026-11-14T00:00:00Z',
}

const imageMeta: FileMetadata = {
  id: 801, objectKey: 'materials/801.png', originalName: 'case-compare.png', mimeType: 'image/png',
  sizeBytes: 245760, sha256: 'a'.repeat(64), classification: 'MATERIAL', institutionId: 91,
  scanStatus: 'CLEAN', uploadedBy: 1, etag: 'etag-1', lastError: null, expiresAt: null,
  createdAt: '2026-11-10T01:00:00Z', updatedAt: '2026-11-10T01:00:00Z', version: 1,
}

const pdfMeta: FileMetadata = {
  id: 802, objectKey: 'materials/802.pdf', originalName: 'visit-guide.pdf', mimeType: 'application/pdf',
  sizeBytes: 1572864, sha256: 'b'.repeat(64), classification: 'MATERIAL', institutionId: null,
  scanStatus: 'CLEAN', uploadedBy: 1, etag: 'etag-2', lastError: null, expiresAt: null,
  createdAt: '2026-11-11T01:00:00Z', updatedAt: '2026-11-11T01:00:00Z', version: 1,
}

const wrappers: VueWrapper[] = []

async function mountPanel(
  chatEntry = 'single_chat_tools',
  overrides: Partial<{ customerId: number | null; externalUserid: string; chatId: string }> = {},
) {
  const wrapper = mount(WecomMaterialPanel, {
    props: {
      customerId: 'customerId' in overrides ? overrides.customerId : 1010,
      externalUserid: 'externalUserid' in overrides ? overrides.externalUserid : 'external-1001',
      chatId: overrides.chatId,
      chatEntry,
    },
  })
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}

describe('企微侧边栏素材库面板', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    grantedPermissions = new Set(['wecom:sidebar:view', 'material:read'])
    vi.mocked(listMaterials).mockResolvedValue({
      items: [imageMaterial, pdfMaterial, offlineMaterial],
      total: 3,
      page: 1,
      size: 50,
    })
    vi.mocked(getFileMetadata).mockImplementation(async (fileId: number) => {
      if (fileId === 801) return imageMeta
      return pdfMeta
    })
    vi.mocked(getMaterialRevisionDownload).mockResolvedValue({
      fileId: 801,
      downloadUrl: 'https://storage.example.test/signed-case.png',
      expiresAt: '2026-11-22T12:00:00Z',
    })
    vi.mocked(uploadWecomMaterialMedia).mockResolvedValue({
      materialId: 21,
      revisionId: 201,
      fileId: 801,
      mediaId: 'MEDIA_IMG_001',
      mediaType: 'image',
      fileName: 'case-compare.png',
      contentType: 'image/png',
      sizeBytes: 245760,
      cached: false,
      expiresAt: '2026-11-25T00:00:00Z',
    })
    vi.mocked(sendImageToCurrentChat).mockResolvedValue({ status: 'SDK_SUCCESS', clientResult: 'sendChatMessage:ok' })
    vi.mocked(sendFileToCurrentChat).mockResolvedValue({ status: 'SDK_SUCCESS', clientResult: 'sendChatMessage:ok' })
  })

  afterEach(() => {
    while (wrappers.length) wrappers.pop()?.unmount()
  })

  it('缺少素材查看权限时不发请求并展示无权限提示', async () => {
    grantedPermissions = new Set(['wecom:sidebar:view'])
    const wrapper = await mountPanel()
    expect(wrapper.get('[data-testid="material-no-permission"]').text()).toContain('没有企微侧边栏或素材库查看权限')
    expect(listMaterials).not.toHaveBeenCalled()
  })

  it('仅展示已审核未下架素材，支持全部 / 图片 / 文档筛选与搜索和元数据预览', async () => {
    const wrapper = await mountPanel()

    expect(listMaterials).toHaveBeenCalledWith(expect.objectContaining({ status: 'APPROVED' }))
    expect(wrapper.find('[data-testid="material-item-21"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="material-item-22"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="material-item-23"]').exists()).toBe(false)

    await wrapper.get('[data-testid="material-filter-image"]').trigger('click')
    expect(wrapper.find('[data-testid="material-item-21"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="material-item-22"]').exists()).toBe(false)

    await wrapper.get('[data-testid="material-filter-doc"]').trigger('click')
    expect(wrapper.find('[data-testid="material-item-21"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="material-item-22"]').exists()).toBe(true)

    await wrapper.get('[data-testid="material-filter-all"]').trigger('click')
    await wrapper.get('[data-testid="material-search"]').setValue('visit-guide')
    expect(wrapper.find('[data-testid="material-item-22"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="material-item-21"]').exists()).toBe(false)

    await wrapper.get('[data-testid="material-select-22"]').trigger('click')
    expect(wrapper.get('[data-testid="material-preview-card"]').text()).toContain('visit-guide.pdf')
    expect(wrapper.get('[data-testid="material-preview-card"]').text()).toContain('PDF 文档')
    expect(wrapper.get('[data-testid="material-preview-card"]').text()).toContain('安全扫描已通过')
    expect(wrapper.get('[data-testid="material-preview-card"]').text()).toContain('1.50 MB')
  })

  it('单聊发送图片素材先换取企微 mediaId，再携带 expectedExternalUserid 调用图片发送', async () => {
    const wrapper = await mountPanel('single_chat_tools')
    await wrapper.get('[data-testid="material-send-21"]').trigger('click')
    await flushPromises()

    expect(uploadWecomMaterialMedia).toHaveBeenCalledWith(21)
    expect(sendImageToCurrentChat).toHaveBeenCalledWith('MEDIA_IMG_001', { expectedExternalUserid: 'external-1001' })
    expect(sendFileToCurrentChat).not.toHaveBeenCalled()
    expect(wrapper.get('[data-testid="material-feedback"]').text()).toContain('企微已确认将「光子嫩肤术后对比图」发送到当前会话')
  })

  it('群聊发送文档素材携带 expectedChatId 并调用文件发送', async () => {
    vi.mocked(uploadWecomMaterialMedia).mockResolvedValueOnce({
      materialId: 22,
      revisionId: 202,
      fileId: 802,
      mediaId: 'MEDIA_FILE_002',
      mediaType: 'file',
      fileName: 'visit-guide.pdf',
      contentType: 'application/pdf',
      sizeBytes: 1572864,
      cached: true,
      expiresAt: '2026-11-25T00:00:00Z',
    })
    const wrapper = await mountPanel('group_chat_tools', { customerId: null, externalUserid: '', chatId: 'wr-group-88' })
    await wrapper.get('[data-testid="material-send-22"]').trigger('click')
    await flushPromises()

    expect(uploadWecomMaterialMedia).toHaveBeenCalledWith(22)
    expect(sendFileToCurrentChat).toHaveBeenCalledWith('MEDIA_FILE_002', { expectedChatId: 'wr-group-88' })
    expect(sendImageToCurrentChat).not.toHaveBeenCalled()
    expect(wrapper.get('[data-testid="material-feedback"]').text()).toContain('发送到当前群聊')
  })

  it('后端返回 PENDING_BUSINESS_RULE 时明确提示未配置，绝不调用 SDK 或伪造成功', async () => {
    vi.mocked(uploadWecomMaterialMedia).mockRejectedValueOnce(
      new ApiError({ status: 422, code: 'PENDING_BUSINESS_RULE', message: '企微官方应用未启用' }),
    )
    const wrapper = await mountPanel('single_chat_tools')
    await wrapper.get('[data-testid="material-send-21"]').trigger('click')
    await flushPromises()

    expect(sendImageToCurrentChat).not.toHaveBeenCalled()
    expect(sendFileToCurrentChat).not.toHaveBeenCalled()
    expect(wrapper.get('[data-testid="material-error"]').text()).toContain('尚未配置')
    expect(wrapper.get('[data-testid="material-error"]').text()).toContain('企微官方应用未启用')
    expect(wrapper.find('[data-testid="material-feedback"]').exists()).toBe(false)
  })

  it('发送遇到会话切换 mismatch 时发出 context-mismatch 并提示阻止误发', async () => {
    vi.mocked(sendImageToCurrentChat).mockResolvedValueOnce({
      status: 'SDK_FAILED',
      mismatch: true,
      clientResult: 'sendChatMessage:fail context_mismatch',
    })
    const wrapper = await mountPanel('single_chat_tools')
    await wrapper.get('[data-testid="material-send-21"]').trigger('click')
    await flushPromises()

    expect(wrapper.emitted('context-mismatch')).toHaveLength(1)
    expect(wrapper.get('[data-testid="material-error"]').text()).toContain('已阻止误发')
  })

  it('客户资料页隐藏发送按钮，仅保留元数据与私有预览能力', async () => {
    const wrapper = await mountPanel('contact_profile')
    expect(wrapper.find('[data-testid="material-send-21"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('仅可查看素材信息')
  })
})
