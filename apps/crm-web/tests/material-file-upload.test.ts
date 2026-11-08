import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { FileMetadata } from '../src/api/file'
import { completeFileUpload, initFileUpload, putPresignedFile, sha256File } from '../src/api/file'
import FileUploadField from '../src/views/institution/FileUploadField.vue'

vi.mock('../src/api/file', () => ({
  initFileUpload: vi.fn(),
  putPresignedFile: vi.fn(),
  completeFileUpload: vi.fn(),
  getFileMetadata: vi.fn(),
  sha256File: vi.fn(),
  waitForFileClean: vi.fn(),
}))

const clean: FileMetadata = {
  id: 701, objectKey: 'materials/test.md', originalName: 'guide.md', mimeType: 'text/markdown',
  sizeBytes: 20, sha256: 'a'.repeat(64), classification: 'MATERIAL', institutionId: 91,
  scanStatus: 'CLEAN', uploadedBy: 1, etag: 'etag-test', lastError: null, expiresAt: null,
  createdAt: '2026-10-28T00:00:00Z', updatedAt: '2026-10-28T00:00:00Z', version: 1,
}

afterEach(() => {
  vi.clearAllMocks()
  document.body.innerHTML = ''
})

function mountField(classification: 'MATERIAL' | 'CONTRACT') {
  return mount(FileUploadField, {
    props: { modelValue: undefined, institutionId: 91, classification },
    global: { plugins: [ElementPlus] }, attachTo: document.body,
  })
}

async function choose(wrapper: ReturnType<typeof mountField>, file: File) {
  const input = wrapper.get('[data-testid="file-input"]')
  Object.defineProperty(input.element, 'files', { configurable: true, value: [file] })
  await input.trigger('change')
}

describe('素材文本上传边界', () => {
  it('Markdown 使用真实声明类型完成预签名上传与 CLEAN 回执', async () => {
    vi.mocked(sha256File).mockResolvedValue('a'.repeat(64))
    vi.mocked(initFileUpload).mockResolvedValue({
      fileId: 701, objectKey: 'materials/test.md', uploadUrl: 'https://storage.example.test/upload',
      expiresAt: '2026-10-28T01:00:00Z', method: 'PUT', sha256: 'a'.repeat(64),
    })
    vi.mocked(putPresignedFile).mockResolvedValue('etag-test')
    vi.mocked(completeFileUpload).mockResolvedValue(clean)
    const wrapper = mountField('MATERIAL')
    expect(wrapper.get('[data-testid="file-input"]').attributes('accept')).toContain('.md')
    await choose(wrapper, new File(['普通 Markdown 正文'], 'guide.md', { type: 'text/plain' }))
    await wrapper.get('[data-testid="file-upload-submit"]').trigger('click')
    await flushPromises()

    expect(initFileUpload).toHaveBeenCalledWith(expect.objectContaining({
      originalName: 'guide.md', mimeType: 'text/markdown', classification: 'MATERIAL', institutionId: 91,
    }))
    expect(completeFileUpload).toHaveBeenCalledWith({ fileId: 701, etag: 'etag-test' })
    expect(wrapper.emitted('uploaded')?.[0]).toEqual([clean])
    wrapper.unmount()
  })

  it('合同仍拒绝普通文本，不发起上传请求', async () => {
    const wrapper = mountField('CONTRACT')
    await choose(wrapper, new File(['not a contract'], 'contract.txt', { type: 'text/plain' }))
    await wrapper.get('[data-testid="file-upload-submit"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('该文件类别仅接受 PDF')
    expect(initFileUpload).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
