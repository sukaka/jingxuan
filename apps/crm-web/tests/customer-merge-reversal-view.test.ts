import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessage } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { computed, provide } from 'vue'
import type { AuthUser } from '../src/api/auth'
import type { MergeCaseItem } from '../src/api/customer'
import { listMergeCandidates, requestMergeReversal, reviewMergeReversal } from '../src/api/customer'
import { useAuthStore } from '../src/stores/auth'
import CustomerMergeView from '../src/views/CustomerMergeView.vue'

vi.mock('../src/api/customer', () => ({
  confirmMergeCase: vi.fn(),
  createMergeCase: vi.fn(),
  listMergeCandidates: vi.fn(),
  rejectMergeCase: vi.fn(),
  requestMergeReversal: vi.fn(),
  reviewMergeCase: vi.fn(),
  reviewMergeReversal: vi.fn(),
  scanMergeCandidates: vi.fn(),
}))

const user: AuthUser = {
  userId: 1,
  username: 'merge-test',
  displayName: '合并测试管理员',
  userType: 'EMPLOYEE',
  roles: ['ADMIN'],
  permissions: ['customer:merge', 'customer:merge_reverse'],
}

function merge(overrides: Partial<MergeCaseItem> = {}): MergeCaseItem {
  return {
    id: 1008,
    primaryCustomerId: 1,
    secondaryCustomerId: 2,
    primarySummary: 'C001 主客户',
    secondarySummary: 'C002 从客户',
    matchEvidence: { matchedOn: ['MOBILE_HASH'], mobileMasked: '136****5555' },
    status: 'CONFIRMED',
    version: 2,
    createdAt: '2026-10-27T00:00:00Z',
    ...overrides,
  }
}

let current: MergeCaseItem
const wrappers: VueWrapper[] = []

function mountView(permissions = user.permissions) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = { ...user, permissions }
  auth.initialized = true
  const wrapper = mount(CustomerMergeView, {
    global: {
      plugins: [ElementPlus, pinia],
      stubs: {
        teleport: true,
        ElTable: {
          props: { data: { type: Array, default: () => [] } },
          setup(props) {
            provide('mergeRows', computed(() => props.data as MergeCaseItem[]))
          },
          template: '<div><slot /><slot name="append" /></div>',
        },
        ElTableColumn: {
          inject: { mergeRows: { default: () => [] } },
          template: '<div><template v-for="row in mergeRows" :key="row.id"><slot :row="row" /></template></div>',
        },
        ElSelect: { template: '<select><slot /></select>' },
        ElOption: { props: ['label', 'value'], template: '<option :value="value">{{ label }}</option>' },
      },
    },
    attachTo: document.body,
  })
  wrappers.push(wrapper)
  return wrapper
}

async function submitReason(wrapper: VueWrapper, reason: string) {
  await wrapper.get('[data-testid="reversal-reason"]').setValue(reason)
  await wrapper.get('[data-testid="reversal-submit"]').trigger('click')
  await flushPromises()
}

beforeEach(() => {
  current = merge()
  vi.clearAllMocks()
  vi.spyOn(ElMessage, 'success').mockImplementation(() => ({ close: vi.fn() }))
  vi.spyOn(ElMessage, 'warning').mockImplementation(() => ({ close: vi.fn() }))
  vi.mocked(listMergeCandidates).mockImplementation(async (query) => ({
    items: query.status ? [] : [{ ...current }],
    total: query.status ? 0 : 1,
    page: 1,
    size: query.size,
  }))
})

afterEach(() => {
  while (wrappers.length) wrappers.pop()?.unmount()
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})

describe('客户合并更正页面', () => {
  it('申请后由管理员审批，并提交更正单版本而非原合并单版本', async () => {
    vi.mocked(requestMergeReversal).mockImplementation(async () => {
      current = { ...current, reversalId: 31, reversalStatus: 'REQUESTED', reversalVersion: 0 }
      return { id: 31, mergeCaseId: 1008, status: 'REQUESTED', warningMessage: null, version: 0 }
    })
    vi.mocked(reviewMergeReversal).mockResolvedValue({
      id: 31,
      mergeCaseId: 1008,
      status: 'EXECUTED',
      warningMessage: '去重删除的身份和标签请人工复核',
      version: 1,
    })
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('[data-testid="reversal-request-1008"]').trigger('click')
    await submitReason(wrapper, '核实为误合并')
    expect(requestMergeReversal).toHaveBeenCalledWith(1008, '核实为误合并', expect.any(String))
    expect(wrapper.get('[data-testid="reversal-status-1008"]').text()).toBe('待更正审批')

    await wrapper.get('[data-testid="reversal-approve-1008"]').trigger('click')
    await submitReason(wrapper, '同意按快照更正')
    expect(reviewMergeReversal).toHaveBeenCalledWith(31, 'approve', {
      reason: '同意按快照更正',
      version: 0,
    }, expect.any(String))
  })

  it('只有合并权限时可以申请，但不能审批或驳回', async () => {
    current = merge({ reversalId: 32, reversalStatus: 'REQUESTED', reversalVersion: 0 })
    const wrapper = mountView(['customer:merge'])
    await flushPromises()

    expect(wrapper.find('[data-testid="reversal-approve-1008"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="reversal-reject-1008"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="reversal-status-1008"]').text()).toBe('待更正审批')
  })

  it('历史无快照显示警示，并由有权限的管理员提交人工完成说明', async () => {
    current = merge({
      reversalId: 33,
      reversalStatus: 'MANUAL_REVIEW',
      reversalVersion: 1,
      reversalWarning: '仅恢复了从客户，其他关联数据需人工逐项迁移确认',
    })
    vi.mocked(reviewMergeReversal).mockImplementation(async () => {
      current = { ...current, status: 'REVERSED', reversalStatus: 'EXECUTED', reversalVersion: 2 }
      return { id: 33, mergeCaseId: 1008, status: 'EXECUTED', warningMessage: current.reversalWarning ?? null, version: 2 }
    })
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.get('[data-testid="reversal-warning-1008"]').text()).toContain('人工逐项迁移确认')
    await wrapper.get('[data-testid="reversal-complete-1008"]').trigger('click')
    await submitReason(wrapper, '已逐项核对并处理关联数据')
    expect(reviewMergeReversal).toHaveBeenCalledWith(33, 'complete', {
      reason: '已逐项核对并处理关联数据',
      version: 1,
    }, expect.any(String))
    expect(wrapper.get('[data-testid="reversal-status-1008"]').text()).toBe('更正已执行')
    expect(wrapper.text()).toContain('已更正')
  })
})
