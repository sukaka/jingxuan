import { describe, expect, it } from 'vitest'
import { createRequestId } from '../src/utils/requestId'

describe('前端请求编号', () => {
  it('生成后端可透传的标准 ULID 时间部分和随机部分', () => {
    expect(createRequestId(0, new Uint8Array(16))).toBe('00000000000000000000000000')
    expect(createRequestId(1, new Uint8Array(16))).toBe('00000000010000000000000000')
    expect(createRequestId(1, new Uint8Array(16).fill(255))).toBe('0000000001ZZZZZZZZZZZZZZZZ')
    expect(createRequestId()).toMatch(/^[0-9A-HJKMNP-TV-Z]{26}$/)
  })

  it('拒绝无效时间戳和随机字节长度', () => {
    expect(() => createRequestId(-1)).toThrow(RangeError)
    expect(() => createRequestId(0, new Uint8Array(1))).toThrow(RangeError)
  })
})
