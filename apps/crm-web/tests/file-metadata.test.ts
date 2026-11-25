import { describe, expect, it, vi } from 'vitest'
import { sha256File, waitForFileClean } from '../src/api/file'
import type { FileMetadata } from '../src/api/file'
import { ensureRandomUuidPolyfill, sha256HexFallback } from '../src/utils/cryptoFallback'

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
  it('真实计算 Blob SHA-256（含非 HTTPS 上下文纯 JS 兜底与 NIST 向量校验）', async () => {
    await expect(sha256File(new Blob(['abc']))).resolves.toBe('ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad')
    expect(sha256HexFallback(new Uint8Array(0))).toBe('e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855')
    expect(sha256HexFallback(new TextEncoder().encode('abc'))).toBe('ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad')
    expect(sha256HexFallback(new TextEncoder().encode('abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq')))
      .toBe('248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1')

    const binary = new Uint8Array(200)
    for (let i = 0; i < binary.length; i += 1) binary[i] = (i * 37) & 0xff
    const nativeHash = await sha256File(new Blob([binary]))
    expect(sha256HexFallback(binary)).toBe(nativeHash)

    const originalCrypto = globalThis.crypto
    const httpOnlyCrypto = { getRandomValues: originalCrypto.getRandomValues.bind(originalCrypto) } as unknown as Crypto
    vi.stubGlobal('crypto', httpOnlyCrypto)
    try {
      await expect(sha256File(new Blob([binary]))).resolves.toBe(nativeHash)
      expect(typeof globalThis.crypto.randomUUID).toBe('undefined')
      ensureRandomUuidPolyfill()
      expect(typeof globalThis.crypto.randomUUID).toBe('function')
      const id1 = globalThis.crypto.randomUUID()
      const id2 = globalThis.crypto.randomUUID()
      expect(id1).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
      expect(id2).not.toBe(id1)
    } finally {
      vi.unstubAllGlobals()
    }
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
