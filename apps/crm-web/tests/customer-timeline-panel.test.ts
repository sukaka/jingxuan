import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { CustomerTimelineItem } from '../src/api/customerTimeline'
import { getCustomerTimeline } from '../src/api/customerTimeline'
import CustomerTimelinePanel from '../src/views/customer/CustomerTimelinePanel.vue'
import { ApiError } from '../src/types/api'

vi.mock('../src/api/customerTimeline', () => ({ getCustomerTimeline: vi.fn() }))

const items: CustomerTimelineItem[] = [
  {
    type: 'FOLLOWUP', eventKey: '00000000000000000001:FOLLOWUP', sourceId: '1',
    occurredAt: '2026-11-01T01:00:00Z', title: '跟进记录',
    summary: '<img src=x onerror=alert(1)>仅文本摘要',
    details: { channelCode: 'PHONE' }, actorUserId: 2,
  },
  {
    type: 'COMPLAINT', eventKey: '00000000000000000002:COMPLAINT', sourceId: '2',
    occurredAt: '2026-11-01T00:00:00Z', title: '正式客诉', summary: '脱敏处理结论',
    details: { status: 'RESOLVED' }, actorUserId: 1,
  },
]

beforeEach(() => {
  vi.clearAllMocks()
})

describe('CustomerTimelinePanel', () => {
  it('按游标加载更多且把业务文本作为纯文本渲染，不执行 XSS', async () => {
    vi.mocked(getCustomerTimeline)
      .mockResolvedValueOnce({ items, nextCursor: 'cursor-2' })
      .mockResolvedValueOnce({ items: [], nextCursor: null })
    const wrapper = mount(CustomerTimelinePanel, {
      props: { customerId: 42 }, global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    expect(getCustomerTimeline).toHaveBeenCalledWith(42, { size: 20 })
    expect(wrapper.text()).toContain('<img src=x onerror=alert(1)>仅文本摘要')
    expect(wrapper.text()).toContain('沟通方式：电话')
    expect(wrapper.text()).toContain('状态：已处理')
    expect(wrapper.text()).not.toContain('channelCode')
    expect(wrapper.text()).not.toContain('RESOLVED')
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.find('[data-testid="timeline-item"]').exists()).toBe(true)

    await wrapper.get('[data-testid="timeline-more"]').trigger('click')
    await flushPromises()
    expect(getCustomerTimeline).toHaveBeenLastCalledWith(42, { size: 20, cursor: 'cursor-2' })
  })

  it('范围或权限失败显示后端错误，不伪造空时间线', async () => {
    vi.mocked(getCustomerTimeline).mockRejectedValue(new ApiError({
      status: 404, code: 'NOT_FOUND', message: '客户不存在或不可见',
    }))
    const wrapper = mount(CustomerTimelinePanel, {
      props: { customerId: 42 }, global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('客户不存在或不可见')
    expect(wrapper.find('[data-testid="timeline-empty"]').exists()).toBe(false)
  })

  it('切换 customerId 时丢弃旧客户延迟响应，不污染新客户时间线', async () => {
    let resolveA!: (value: { items: CustomerTimelineItem[]; nextCursor: string | null }) => void
    vi.mocked(getCustomerTimeline)
      .mockImplementationOnce(() => new Promise((res) => { resolveA = res }))
      .mockResolvedValueOnce({
        items: [{ ...items[0], eventKey: 'B1', summary: '客户B真实时间线' }],
        nextCursor: null,
      })

    const wrapper = mount(CustomerTimelinePanel, {
      props: { customerId: 10 },
      global: { plugins: [ElementPlus] },
    })
    await wrapper.setProps({ customerId: 20 })
    await flushPromises()

    expect(wrapper.text()).toContain('客户B真实时间线')
    resolveA({ items: [{ ...items[0], eventKey: 'A1', summary: '客户A过期时间线' }], nextCursor: null })
    await flushPromises()
    expect(wrapper.text()).toContain('客户B真实时间线')
    expect(wrapper.text()).not.toContain('客户A过期时间线')
  })
})
