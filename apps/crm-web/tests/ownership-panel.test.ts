import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { describe, expect, it, vi } from 'vitest'
import { listOwnershipHistory, listOwnershipTransfers } from '../src/api/customer'
import { useAuthStore } from '../src/stores/auth'
import OwnershipPanel from '../src/views/customer/OwnershipPanel.vue'

vi.mock('../src/api/customer', () => ({
  listOwnershipTransfers: vi.fn(),
  listOwnershipHistory: vi.fn(),
  listCustomerOwnerOptions: vi.fn(),
  createOwnershipTransfer: vi.fn(),
  approveOwnershipTransfer: vi.fn(),
  rejectOwnershipTransfer: vi.fn(),
}))

describe('OwnershipPanel 转交契约', () => {
  it('申请使用 from/to 字段，历史使用 old/new 字段', async () => {
    vi.mocked(listOwnershipTransfers).mockResolvedValue([
      {
        id: 8,
        customerId: 1,
        fromOwnerId: 2,
        fromOwnerName: '王丽',
        toOwnerId: 3,
        toOwnerName: '李强',
        reason: '跨组交接',
        status: 'PENDING',
        requestedByName: '张敏',
        version: 0,
        createdAt: '2026-10-24T01:00:00Z',
      },
    ])
    vi.mocked(listOwnershipHistory).mockResolvedValue([
      {
        id: 9,
        customerId: 1,
        oldOwnerId: 4,
        oldOwnerName: '张敏',
        newOwnerId: 2,
        newOwnerName: '王丽',
        changedBy: 1,
        changedByName: '系统管理员',
        reason: '历史归属',
        effectiveAt: '2026-10-20T01:00:00Z',
        createdAt: '2026-10-20T01:00:00Z',
      },
    ])
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    auth.user = {
      userId: 1,
      username: 'admin',
      displayName: '系统管理员',
      userType: 'EMPLOYEE',
      roles: ['ADMIN'],
      permissions: ['customer:read', 'customer:transfer'],
    }
    const wrapper = mount(OwnershipPanel, {
      props: { customerId: 1 },
      global: { plugins: [ElementPlus, pinia] },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('王丽')
    expect(wrapper.text()).toContain('李强')
    expect(wrapper.text()).toContain('张敏')
    expect(wrapper.text()).toContain('跨组交接')
    expect(wrapper.get('.ownership-actions').findAll('button').map((button) => button.text())).toEqual(['批准', '拒绝'])
    const actionColumn = wrapper.findAllComponents({ name: 'ElTableColumn' })
      .find((column) => column.props('label') === '操作')
    expect(Number(actionColumn?.props('minWidth'))).toBe(230)
  })
})
