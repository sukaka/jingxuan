import { describe, expect, it } from 'vitest'
import { issueTypeLabel, mappingStatusLabel, mappingStatusTone } from '../src/views/youzan/youzanUi'

describe('有赞映射状态展示边界', () => {
  it('异常类型使用中文展示', () => {
    expect(issueTypeLabel('MAPPING_FAILED')).toBe('SKU 映射失败')
    expect(issueTypeLabel('CUSTOMER_UNRESOLVED')).toBe('客户待人工匹配')
    expect(issueTypeLabel('PAYMENT_MISMATCH')).toBe('支付核验异常')
  })

  it('PENDING_MAPPING 和 ERROR 不显示成功', () => {
    expect(mappingStatusLabel('PENDING_MAPPING')).toBe('待映射处理')
    expect(mappingStatusTone('PENDING_MAPPING')).toBe('warning')
    expect(mappingStatusLabel('ERROR')).toBe('同步异常')
    expect(mappingStatusTone('ERROR')).toBe('danger')
  })

  it('未确认状态保留原始代码，不擅自显示成功', () => {
    expect(mappingStatusLabel('UNKNOWN_STATUS')).toBe('UNKNOWN_STATUS')
    expect(mappingStatusTone('UNKNOWN_STATUS')).toBe('info')
  })
})
