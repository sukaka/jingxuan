import { describe, expect, it, vi } from 'vitest'
import { sha256File, waitForFileClean } from '../src/api/file'
import type { FileMetadata } from '../src/api/file'

function metadata(status: string): FileMetadata {
  return {
    id: 701,
    objectKey: 'contracts/1/test.pdf',
    originalName: 'test.pdf',
    mimeType: 'application/pdf',
    sizeBytes: 3,
    sha256: 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad',
    classification: 'CONTRACT',
    institutionId: 91,
    scanStatus: status,
    uploadedBy: 1,
    etag: 'etag-test',
    lastError: status === 'BLOCKED' ? 'CLAMAV_FOUND' : null,
    expiresAt: null,
    createdAt: '2026-10-24T01:00:00Z',
    updatedAt: '2026-10-24T01:00:00Z',
    version: 1,
  }
}

describe('文件哈希与扫描元数据', () => {
  it('真实计算 Blob SHA-256', async () => {
    await expect(sha256File(new Blob(['abc']))).resolves.toBe('ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad')
  })

  it('PENDING 后查询到 CLEAN 才返回', async () => {
    const fetchMetadata = vi.fn()
      .mockResolvedValueOnce(metadata('PENDING'))
      .mockResolvedValueOnce(metadata('CLEAN'))

    const result = await waitForFileClean(701, { maxAttempts: 2, fetchMetadata, sleep: async () => {} })

    expect(result.scanStatus).toBe('CLEAN')
    expect(fetchMetadata).toHaveBeenCalledTimes(2)
  })

  it('BLOCKED 直接报告真实扫描失败，PENDING 超时保留重试语义', async () => {
    const blocked = vi.fn().mockResolvedValue(metadata('BLOCKED'))
    await expect(waitForFileClean(701, { fetchMetadata: blocked, sleep: async () => {} })).rejects.toMatchObject({ code: 'FILE_SCAN_FAILED', message: 'CLAMAV_FOUND' })

    const pending = vi.fn().mockResolvedValue(metadata('PENDING'))
    await expect(waitForFileClean(701, { maxAttempts: 2, fetchMetadata: pending, sleep: async () => {} })).rejects.toMatchObject({ code: 'FILE_SCAN_PENDING' })
  })
})
