import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { FileMetadata } from '../src/api/file'
import {
  completeFileUpload,
  initFileUpload,
  putPresignedFile,
  sha256File,
  waitForFileClean,
} from '../src/api/file'
import { ApiError } from '../src/types/api'
import FileUploadField from '../src/views/institution/FileUploadField.vue'

vi.mock('../src/api/file', () => ({
  sha256File: vi.fn(),
  initFileUpload: vi.fn(),
  putPresignedFile: vi.fn(),
  completeFileUpload: vi.fn(),
  getFileMetadata: vi.fn(),
  waitForFileClean: vi.fn(),
}))

function metadata(status: string): FileMetadata {
  return {
    id: 701,
    objectKey: 'contracts/1/test.pdf',
    originalName: 'contract.pdf',
    mimeType: 'application/pdf',
    sizeBytes: 4,
    sha256: 'a'.repeat(64),
    classification: 'CONTRACT',
    institutionId: 91,
    scanStatus: status,
    uploadedBy: 1,
    etag: 'etag-real',
    lastError: null,
    expiresAt: null,
    createdAt: '2026-10-24T01:00:00Z',
    updatedAt: '2026-10-24T01:00:00Z',
    version: 1,
  }
}

const wrappers: VueWrapper[] = []
afterEach(() => {
  while (wrappers.length) wrappers.pop()?.unmount()
  document.body.innerHTML = ''
})

async function mountAndSelect() {
  const wrapper = mount(FileUploadField, {
    props: { modelValue: undefined, institutionId: 91, classification: 'CONTRACT' },
    global: { plugins: [ElementPlus] },
    attachTo: document.body,
  })
  wrappers.push(wrapper)
  const input = wrapper.get('[data-testid="file-input"]')
  const file = new File(['pdf'], 'contract.pdf', { type: 'application/pdf' })
  Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
  await input.trigger('change')
  await flushPromises()
  return { wrapper, file }
}

describe('FileUploadField 真实文件生命周期', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(sha256File).mockResolvedValue('a'.repeat(64))
    vi.mocked(initFileUpload).mockResolvedValue({
      fileId: 701,
      objectKey: 'contracts/1/test.pdf',
      uploadUrl: 'https://storage.example.test/upload',
      expiresAt: '2026-10-24T02:00:00Z',
      method: 'PUT',
      sha256: 'a'.repeat(64),
    })
    vi.mocked(putPresignedFile).mockResolvedValue('etag-real')
    vi.mocked(completeFileUpload).mockResolvedValue(metadata('PENDING'))
    vi.mocked(waitForFileClean).mockResolvedValue(metadata('CLEAN'))
  })

  it('init→预签名 PUT→complete→CLEAN，只有 CLEAN 才向父表单发 fileId', async () => {
    const { wrapper, file } = await mountAndSelect()

    await wrapper.get('[data-testid="file-upload-submit"]').trigger('click')
    await flushPromises()

    expect(initFileUpload).toHaveBeenCalledWith({
      originalName: 'contract.pdf',
      mimeType: 'application/pdf',
      sizeBytes: 3,
      sha256: 'a'.repeat(64),
      classification: 'CONTRACT',
      institutionId: 91,
    })
    expect(putPresignedFile).toHaveBeenCalledWith({ url: 'https://storage.example.test/upload', file })
    expect(completeFileUpload).toHaveBeenCalledWith({ fileId: 701, etag: 'etag-real' })
    expect(waitForFileClean).toHaveBeenCalledWith(701)
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual([701])
    expect(wrapper.text()).toContain('扫描通过 · 文件 ID 701')
  })

  it('PUT 失败按原初始化记录重试，不重复 init 或伪造 fileId', async () => {
    vi.mocked(putPresignedFile)
      .mockRejectedValueOnce(new ApiError({ status: 0, code: 'NETWORK_ERROR', message: 'PUT 失败测试' }))
      .mockResolvedValueOnce('etag-real')
    vi.mocked(completeFileUpload).mockResolvedValue(metadata('CLEAN'))
    const { wrapper } = await mountAndSelect()

    await wrapper.get('[data-testid="file-upload-submit"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('PUT 失败测试')
    expect(wrapper.emitted('update:modelValue')?.at(-1)).not.toEqual([701])

    await wrapper.get('[data-testid="file-upload-submit"]').trigger('click')
    await flushPromises()
    expect(initFileUpload).toHaveBeenCalledTimes(1)
    expect(putPresignedFile).toHaveBeenCalledTimes(2)
    expect(completeFileUpload).toHaveBeenCalledTimes(1)
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual([701])
  })

  it('扫描超时只重查扫描，不重复 complete', async () => {
    vi.mocked(waitForFileClean)
      .mockRejectedValueOnce(new ApiError({ status: 408, code: 'FILE_SCAN_PENDING', message: '扫描中' }))
      .mockResolvedValueOnce(metadata('CLEAN'))
    const { wrapper } = await mountAndSelect()

    await wrapper.get('[data-testid="file-upload-submit"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('扫描中')
    await wrapper.get('[data-testid="file-upload-submit"]').trigger('click')
    await flushPromises()

    expect(completeFileUpload).toHaveBeenCalledTimes(1)
    expect(waitForFileClean).toHaveBeenCalledTimes(2)
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual([701])
  })
})
