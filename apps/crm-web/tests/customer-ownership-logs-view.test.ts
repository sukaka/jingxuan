import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  listCustomerOwnerOptions,
  listCustomerOwnershipLogs,
  listScopedOwnershipTransfers,
} from '../src/api/customer'
import { useAuthStore } from '../src/stores/auth'
import CustomerOwnershipLogsView from '../src/views/CustomerOwnershipLogsView.vue'

vi.mock('../src/api/customer', () => ({
  listCustomerOwnershipLogs: vi.fn(),
  listScopedOwnershipTransfers: vi.fn(),
  listCustomerOwnerOptions: vi.fn(),
  approveOwnershipTransfer: vi.fn(),
  rejectOwnershipTransfer: vi.fn(),
}))

describe('CustomerOwnershipLogsView 客户归属日志', () => {
  beforeEach(() => {
    vi.mocked(listCustomerOwnerOptions).mockReset().mockResolvedValue([
      { id: 2, displayName: '王丽', teamId: 1, teamName: '管家一组' },
      { id: 12, displayName: '周小洁', teamId: 1, teamName: '管家一组' },
    ])
    vi.mocked(listCustomerOwnershipLogs).mockReset().mockResolvedValue({
      items: [
        {
          id: 501,
          customerId: 10,
          customerNo: 'C0000000010',
          customerName: '陈女士',
          oldOwnerId: 2,
          oldOwnerName: '王丽',
          newOwnerId: 12,
          newOwnerName: '周小洁',
          changedBy: 4,
          changedByName: '张敏',
          reason: '休假代管',
          approvalRef: 'DIRECT_ASSIGN',
          changeMode: 'DIRECT_ASSIGN',
          effectiveAt: '2026-11-26T08:00:00Z',
          createdAt: '2026-11-26T08:00:00Z',
        },
      ],
      total: 1,
      page: 1,
      size: 20,
    })
    vi.mocked(listScopedOwnershipTransfers).mockReset().mockResolvedValue({
      items: [
        {
          id: 901,
          customerId: 11,
          customerNo: 'C0000000011',
          customerName: '林女士',
          fromOwnerId: 2,
          fromOwnerName: '王丽',
          toOwnerId: 4,
          toOwnerName: '张敏',
          requestedByName: '张敏',
          reviewedByName: null,
          reason: '待审批转交',
          status: 'PENDING',
          version: 0,
          createdAt: '2026-11-26T08:10:00Z',
          effectiveAt: null,
        },
      ],
      total: 1,
      page: 1,
      size: 20,
    })
  })

  it('默认加载分页归属历史并展示直接分配方式，切至转交审批单按需加载审批列表', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    auth.user = {
      userId: 4,
      username: 'lead1',
      displayName: '张敏',
      userType: 'EMPLOYEE',
      roles: ['MANAGER_LEAD'],
      permissions: ['customer:read', 'customer:transfer'],
    }
    const wrapper = mount(CustomerOwnershipLogsView, {
      global: { plugins: [ElementPlus, pinia] },
    })
    await flushPromises()

    expect(listCustomerOwnershipLogs).toHaveBeenCalledWith({
      page: 1,
      size: 20,
      keyword: undefined,
      ownerId: undefined,
    })
    expect(wrapper.text()).toContain('陈女士')
    expect(wrapper.text()).toContain('主管直接分配')
    expect(wrapper.text()).toContain('王丽')
    expect(wrapper.text()).toContain('周小洁')
    expect(listScopedOwnershipTransfers).not.toHaveBeenCalled()

    const tabs = wrapper.findComponent({ name: 'ElTabs' })
    await tabs.vm.$emit('update:modelValue', 'transfers')
    await flushPromises()

    expect(listScopedOwnershipTransfers).toHaveBeenCalledWith({
      page: 1,
      size: 20,
      status: undefined,
      ownerId: undefined,
      keyword: undefined,
    })
    expect(wrapper.text()).toContain('林女士')
    expect(wrapper.text()).toContain('待审批')
  })
})
