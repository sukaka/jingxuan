import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { extractUnmappedExternalSkuId, issueTypeLabel, mappingStatusLabel, mappingStatusTone } from '../src/views/youzan/youzanUi'

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

  it('未确认状态展示自然中文兜底，不擅自显示成功', () => {
    expect(mappingStatusLabel('UNKNOWN_STATUS')).toBe('未知状态')
    expect(mappingStatusTone('UNKNOWN_STATUS')).toBe('info')
  })

  it('正确从 MAPPING_FAILED 错误中提取外部 SKU ID 并忽略非匹配错误', () => {
    expect(extractUnmappedExternalSkuId('外部SKU未映射或数量无效: 5505085839:26076889518')).toBe('5505085839:26076889518')
    expect(extractUnmappedExternalSkuId('外部 SKU 未映射或数量无效：5505085839:26076889518，请先配置')).toBe('5505085839:26076889518')
    expect(extractUnmappedExternalSkuId('外部 SKU 未映射：YZ-SKU-404')).toBe('YZ-SKU-404')
    expect(extractUnmappedExternalSkuId('外部SKU未映射: YZ-SKU-MOCK-01; 待处理')).toBe('YZ-SKU-MOCK-01')
    expect(extractUnmappedExternalSkuId('外部SKU未映射或数量无效: 5505085839:26076889518。')).toBe('5505085839:26076889518')

    expect(extractUnmappedExternalSkuId('外部 SKU 未映射')).toBeNull()
    expect(extractUnmappedExternalSkuId('外部 SKU 未映射：null')).toBeNull()
    expect(extractUnmappedExternalSkuId('外部SKU未映射: NULL。')).toBeNull()
    expect(extractUnmappedExternalSkuId('客户未匹配: yz-open-001')).toBeNull()
    expect(extractUnmappedExternalSkuId('支付金额不一致: expected=362200 actual=100')).toBeNull()
    expect(extractUnmappedExternalSkuId('')).toBeNull()
    expect(extractUnmappedExternalSkuId(null)).toBeNull()
    expect(extractUnmappedExternalSkuId(undefined)).toBeNull()
  })

  it('youzan.css 不依赖未挂载的 .crm-ui 祖先类名，确保拉取条数与店铺快捷按钮样式生效', () => {
    const css = readFileSync(resolve(__dirname, '../src/views/youzan/youzan.css'), 'utf8')
    expect(css).not.toContain('.crm-ui')
    expect(css).toContain('.youzan-pull-control')
    expect(css).toContain('.youzan-pull-preset')
    expect(css).toContain('.youzan-pull-preset.is-active')
    expect(css).toContain('.youzan-quick-shop-chip')
    expect(css).toContain('.youzan-quick-shop-chip.is-active')
  })
})
