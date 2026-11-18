import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  createMonthlyDispatch,
  executeMonthlyDispatch,
  previewMonthlyDispatch,
} from '../src/api/followup-automation'
import MonthlyDispatchView from '../src/views/MonthlyDispatchView.vue'

vi.mock('../src/api/followup-automation', () => ({
  previewMonthlyDispatch: vi.fn(),
  createMonthlyDispatch: vi.fn(),
  executeMonthlyDispatch: vi.fn(),
  retryMonthlyDispatch: vi.fn(),
}))

const preview = {
  orderMonth: '2026-08',
  dispatchMonth: '2026-11',
  items: [{ customerId: 3, customerName: '合成客户', ownerStaffId: 2, teamId: 1, dueAt: '2026-11-15T02:00:00Z', orderIds: [11, 12] }],
  total: 1,
  page: 1,
  size: 20,
}
const ready = {
  id: 9,
  orderMonth: '2026-08',
  dispatchMonth: '2026-11',
  status: 'READY',
  version: 0,
  retryCount: 0,
  items: [{ ...preview.items[0], id: 90, status: 'PENDING', taskId: null, reason: null }],
}
const completed = {
  ...ready,
  status: 'COMPLETED',
  version: 1,
  items: [{ ...ready.items[0], status: 'CREATED', taskId: 99 }],
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(previewMonthlyDispatch).mockResolvedValue(preview)
  vi.mocked(createMonthlyDispatch).mockResolvedValue(ready)
  vi.mocked(executeMonthlyDispatch).mockResolvedValue(completed)
})

describe('MonthlyDispatchView 月度回访批次', () => {
  it('严格按预览、创建、执行顺序操作并展示多订单关联', async () => {
    const wrapper = mount(MonthlyDispatchView, { global: { plugins: [ElementPlus] } })
    expect(wrapper.get('[data-testid="create-batch"]').attributes('disabled')).toBeDefined()

    await wrapper.get('[data-testid="order-month"]').setValue('2026-08')
    await wrapper.get('[data-testid="preview-batch"]').trigger('click')
    await flushPromises()
    expect(previewMonthlyDispatch).toHaveBeenCalledWith({ orderMonth: '2026-08', page: 1, size: 20 })
    expect(wrapper.text()).toContain('2 笔订单')
    expect(wrapper.text()).toContain('2026-11')

    await wrapper.get('[data-testid="create-batch"]').trigger('click')
    await flushPromises()
    expect(createMonthlyDispatch).toHaveBeenCalledWith({ orderMonth: '2026-08' }, expect.any(String))
    expect(wrapper.text()).toContain('READY')

    await wrapper.get('[data-testid="execute-batch"]').trigger('click')
    await flushPromises()
    expect(executeMonthlyDispatch).toHaveBeenCalledWith(9, 0, expect.any(String))
    expect(wrapper.text()).toContain('COMPLETED')
    expect(wrapper.text()).toContain('任务 #99')
  })
})
