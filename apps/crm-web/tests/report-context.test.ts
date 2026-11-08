import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ReportContextBar from '../src/views/reporting/ReportContextBar.vue'
import { displayDateTime, metricVersionText } from '../src/views/reporting/reportUi'

const context = {
  from: '2026-10-01', to: '2026-10-28',
  fromUtc: '2026-09-30T16:00:00Z', toUtcExclusive: '2026-10-28T16:00:00Z',
  timeZone: 'Asia/Shanghai', sourceSystem: 'ALL', receiverType: 'ALL',
  metricVersion: 'P2_FACT_V1', sourceWatermark: '2026-10-28T11:00:27Z',
}

describe('报表时间与统计口径展示', () => {
  it('时间使用本地年月日时分秒，不保留 ISO 时区后缀', () => {
    expect(displayDateTime('2026-10-28T19:00:27')).toBe('2026-10-28 19:00:27')
    expect(displayDateTime('2026-10-28T11:00:27Z')).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/)
    expect(displayDateTime('2026-10-28T11:00:27Z')).not.toContain('Z')
    expect(displayDateTime(null)).toBe('待确认')
  })

  it('内部指标版本转换为中文统计口径', () => {
    expect(metricVersionText('P2_FACT_V1')).toBe('统计口径 V1')
    expect(metricVersionText('P2_FACT_V12')).toBe('统计口径 V12')
    expect(metricVersionText('OTHER')).toBe('自定义统计口径')
  })

  it('上下文不展示时区、UTC 边界或内部版本编码', () => {
    const wrapper = mount(ReportContextBar, { props: { context } })
    expect(wrapper.text()).toContain('统计口径 V1')
    expect(wrapper.text()).toContain('数据更新至')
    expect(wrapper.text()).not.toContain('UTC')
    expect(wrapper.text()).not.toContain('Asia/Shanghai')
    expect(wrapper.text()).not.toContain('P2_FACT_V1')
    expect(wrapper.text()).not.toContain('fromUtc')
    expect(wrapper.text()).toMatch(/数据更新至\s*\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}/)
  })
})
