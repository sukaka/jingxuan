import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  assignCustomerOwner,
  batchAssignCustomerOwners,
  listCustomerOwnerOptions,
} from '../src/api/customer'
import { useAuthStore } from '../src/stores/auth'
import CustomerAssignDialog from '../src/views/customer/CustomerAssignDialog.vue'
import type { CustomerAssignTarget } from '../src/views/customer/CustomerAssignDialog.vue'

vi.mock('../src/api/customer', () => ({
  listCustomerOwnerOptions: vi.fn(),
  assignCustomerOwner: vi.fn(),
  batchAssignCustomerOwners: vi.fn(),
}))

const customerA: CustomerAssignTarget = {
  id: 101,
  customerNo: 'C0000000101',
  displayName: 'A客户',
  nickname: '小A',
  ownerStaffId: 2,
  ownerName: 'B管家',
  version: 3,
}

const customerB: CustomerAssignTarget = {
  id: 102,
  customerNo: 'C0000000102',
  displayName: 'D客户',
  nickname: '小D',
  ownerStaffId: 12,
  ownerName: 'C管家',
  version: 1,
}

function mountDialog(customers: CustomerAssignTarget[]) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = {
    userId: 4,
    username: 'lead1',
    displayName: '张主管',
    userType: 'EMPLOYEE',
    roles: ['MANAGER_LEAD'],
    permissions: ['customer:read', 'customer:transfer'],
  }
  return mount(CustomerAssignDialog, {
    props: { modelValue: false, customers },
    global: {
      plugins: [ElementPlus, pinia],
      stubs: {
        ElDialog: {
          name: 'ElDialog',
          props: ['modelValue', 'title'],
          template: '<div v-if="modelValue" class="el-dialog-stub"><slot /><slot name="footer" /></div>',
        },
        ElSelect: {
          name: 'ElSelect',
          props: {
            modelValue: { type: [String, Number], default: undefined },
            disabled: Boolean,
          },
          emits: ['update:modelValue', 'change'],
          template: '<div class="el-select-stub"><slot /></div>',
        },
        ElOption: {
          name: 'ElOption',
          props: ['label', 'value', 'disabled'],
          template: '<div class="el-option-stub" :data-value="value" :data-disabled="String(Boolean(disabled))">{{ label }}</div>',
        },
      },
    },
  })
}

describe('CustomerAssignDialog 主管分配客户弹窗', () => {
  beforeEach(() => {
    vi.mocked(listCustomerOwnerOptions).mockReset().mockResolvedValue([
      { id: 2, displayName: 'B管家', teamId: 1, teamName: '管家一组' },
      { id: 12, displayName: 'C管家', teamId: 1, teamName: '管家一组' },
    ])
    vi.mocked(assignCustomerOwner).mockReset()
    vi.mocked(batchAssignCustomerOwners).mockReset()
  })

  it('单客模式将 A 客户由 B 管家直接分配给 C 管家并携带幂等键与版本', async () => {
    vi.mocked(assignCustomerOwner).mockResolvedValue({
      historyId: 88,
      customerId: 101,
      customerNo: 'C0000000101',
      customerName: 'A客户',
      oldOwnerId: 2,
      oldOwnerName: 'B管家',
      newOwnerId: 12,
      newOwnerName: 'C管家',
      oldTeamId: 1,
      newTeamId: 1,
      changedBy: 4,
      changedByName: '张主管',
      reason: 'B管家休假交接',
      approvalRef: 'DIRECT_ASSIGN',
      effectiveAt: '2026-11-26T08:00:00Z',
      customerVersion: 4,
    })

    const wrapper = mountDialog([customerA])
    await wrapper.setProps({ modelValue: true })
    await flushPromises()

    expect(wrapper.find('[data-testid="assign-single-summary"]').text()).toContain('A客户')
    expect(wrapper.find('[data-testid="assign-single-summary"]').text()).toContain('B管家')

    const options = wrapper.findAllComponents({ name: 'ElOption' })
    const currentOption = options.find((opt) => opt.props('value') === 2)
    const targetOption = options.find((opt) => opt.props('value') === 12)
    expect(currentOption?.props('disabled')).toBe(true)
    expect(targetOption?.props('disabled')).toBe(false)

    const select = wrapper.findComponent({ name: 'ElSelect' })
    await select.vm.$emit('update:modelValue', 12)
    const textarea = wrapper.find('textarea')
    await textarea.setValue('  B管家休假交接  ')
    await wrapper.find('[data-testid="assign-submit-btn"]').trigger('click')
    await flushPromises()

    expect(assignCustomerOwner).toHaveBeenCalledWith(
      101,
      { newOwnerId: 12, reason: 'B管家休假交接', version: 3 },
      expect.any(String),
    )
    expect(wrapper.emitted('assigned')).toHaveLength(1)
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual([false])
  })

  it('批量模式提示将自动跳过的同归属客户并调用批量分配接口', async () => {
    vi.mocked(batchAssignCustomerOwners).mockResolvedValue({
      batchRef: 'BATCH-123456',
      newOwnerId: 12,
      newOwnerName: 'C管家',
      newTeamId: 1,
      reason: '团队负载均衡',
      totalCount: 2,
      assignedCount: 1,
      skippedCount: 1,
      effectiveAt: '2026-11-26T08:00:00Z',
      items: [],
    })

    const wrapper = mountDialog([customerA, customerB])
    await wrapper.setProps({ modelValue: true })
    await flushPromises()

    expect(wrapper.find('[data-testid="assign-batch-summary"]').text()).toContain('已选择 2 位客户')
    const select = wrapper.findComponent({ name: 'ElSelect' })
    await select.vm.$emit('update:modelValue', 12)
    await flushPromises()

    expect(wrapper.text()).toContain('其中 1 位已归属目标管家，提交时将自动跳过，实际变更 1 位')
    await wrapper.find('textarea').setValue('团队负载均衡')
    await wrapper.find('[data-testid="assign-submit-btn"]').trigger('click')
    await flushPromises()

    expect(batchAssignCustomerOwners).toHaveBeenCalledWith(
      {
        items: [
          { customerId: 101, version: 3 },
          { customerId: 102, version: 1 },
        ],
        newOwnerId: 12,
        reason: '团队负载均衡',
      },
      expect.any(String),
    )
    expect(wrapper.emitted('assigned')).toHaveLength(1)
  })
})
