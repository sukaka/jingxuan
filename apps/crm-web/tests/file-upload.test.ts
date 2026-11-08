import { afterEach, describe, expect, it, vi } from 'vitest'
import { putPresignedFile } from '../src/api/file'
import { ApiError } from '../src/types/api'

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('预签名 PUT 文件上传', () => {
  it('使用返回的 URL、headers 与真实 Blob，并返回真实 ETag', async () => {
    const file = new Blob(['contract-test'], { type: 'application/pdf' })
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 200, headers: { ETag: 'etag-real-test' } }))
    vi.stubGlobal('fetch', fetchMock)

    const etag = await putPresignedFile({
      url: 'https://storage.example.test/signed-upload',
      headers: { 'Content-Type': 'application/pdf', 'x-test-signature': 'signed' },
      file,
    })

    expect(etag).toBe('etag-real-test')
    expect(fetchMock).toHaveBeenCalledWith('https://storage.example.test/signed-upload', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/pdf', 'x-test-signature': 'signed' },
      body: file,
    })
    const options = fetchMock.mock.calls[0]?.[1] as RequestInit
    expect(options).not.toHaveProperty('credentials')
  })

  it('响应缺少 ETag 时禁止继续 complete，不伪造标识', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 200 })))

    const promise = putPresignedFile({ url: 'https://storage.example.test/upload', file: new Blob(['x']) })

    await expect(promise).rejects.toMatchObject({ code: 'PRESIGNED_ETAG_MISSING' })
  })

  it('上传失败保留真实 HTTP 错误且不泄露签名 URL', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('upload denied', { status: 403 })))

    const promise = putPresignedFile({ url: 'https://storage.example.test/secret-signature', file: new Blob(['x']) })

    await expect(promise).rejects.toMatchObject({ status: 403, code: 'PRESIGNED_UPLOAD_FAILED', message: 'upload denied' })
    await expect(promise).rejects.not.toThrow('secret-signature')
  })

  it('网络失败归为可重试错误', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('offline')))

    const promise = putPresignedFile({ url: 'https://storage.example.test/upload', file: new Blob(['x']) })

    await expect(promise).rejects.toBeInstanceOf(ApiError)
    await expect(promise).rejects.toMatchObject({ status: 0, code: 'PRESIGNED_UPLOAD_FAILED' })
  })
})
