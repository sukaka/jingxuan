import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessage } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { computed, provide } from 'vue'
import type { AuthUser } from '../src/api/auth'
import type { MergeCaseItem, MultiStewardCustomerGroupItem } from '../src/api/customer'
import {
  getMultiStewardSummary,
  listMergeCandidates,
  listMultiStewardCustomers,
  requestMergeReversal,
  reviewMergeReversal,
} from '../src/api/customer'
import { useAuthStore } from '../src/stores/auth'
import CustomerMergeView from '../src/views/CustomerMergeView.vue'

vi.mock('../src/api/customer', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../src/api/customer')>()),
  getMultiStewardSummary: vi.fn(),
  listMultiStewardCustomers: vi.fn(),
  listMergeCandidates: vi.fn(),
  requestMergeReversal: vi.fn(),
  reviewMergeReversal: vi.fn(),
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

const sampleMultiGroup: MultiStewardCustomerGroupItem = {
  groupKey: 'MULTI-MOBILE-1',
  primaryCustomerId: 101,
  primaryCustomerNo: 'C0000000101',
  displayName: '陈女士',
  nickname: '小鱼',
  mobileMasked: '138****5678',
  stewardCount: 3,
  inScopeStewardCount: 2,
  crossTeamStewardCount: 1,
  crossTeam: true,
  matchReasons: ['MOBILE_HASH', 'WECOM_CONTACT'],
  evidenceMasked: '138****5678',
  latestAddedAt: '2026-11-26T08:00:00Z',
  stewards: [
    {
      staffUserId: 2,
      staffName: '王丽',
      teamId: 1,
      teamName: '管家一组',
      inMyScope: true,
      customerId: 101,
      customerNo: 'C0000000101',
      customerName: '陈女士',
      relationTypes: ['CUSTOMER_OWNER', 'WECOM_FOLLOW'],
      addedAt: '2026-11-20T09:00:00Z',
    },
    {
      staffUserId: 12,
      staffName: '周小洁',
      teamId: 1,
      teamName: '管家一组',
      inMyScope: true,
      customerId: 102,
      customerNo: 'C0000000102',
      customerName: '陈女士',
      relationTypes: ['CUSTOMER_OWNER'],
      addedAt: '2026-11-21T10:00:00Z',
    },
    {
      staffUserId: 20,
      staffName: '李青',
      teamId: 2,
      teamName: '管家二组',
      inMyScope: false,
      customerId: null,
      customerNo: null,
      customerName: null,
      relationTypes: ['CUSTOMER_OWNER'],
      addedAt: '2026-11-26T08:00:00Z',
    },
  ],
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
        CustomerDetailDrawer: true,
        ElTable: {
          props: { data: { type: Array, default: () => [] } },
          setup(props) {
            provide('tableRows', computed(() => props.data as Record<string, unknown>[]))
          },
          template: '<div><slot /><slot name="append" /></div>',
        },
        ElTableColumn: {
          inject: { tableRows: { default: () => [] } },
          template: '<div><template v-for="(row, idx) in tableRows" :key="row.id ?? row.groupKey ?? idx"><slot :row="row" /></template></div>',
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

async function switchToHistoryTab(wrapper: VueWrapper) {
  const tabs = wrapper.findComponent({ name: 'ElTabs' })
  await tabs.vm.$emit('update:modelValue', 'history-reversals')
  await flushPromises()
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
  vi.mocked(getMultiStewardSummary).mockResolvedValue({
    multiStewardCustomerCount: 4,
    crossTeamCustomerCount: 2,
    maxStewardCount: 3,
  })
  vi.mocked(listMultiStewardCustomers).mockResolvedValue({
    items: [sampleMultiGroup],
    total: 1,
    page: 1,
    size: 20,
  })
  vi.mocked(listMergeCandidates).mockImplementation(async () => ({
    items: [{ ...current }],
    total: 1,
    page: 1,
    size: 20,
  }))
})

afterEach(() => {
  while (wrappers.length) wrappers.pop()?.unmount()
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})

describe('客户唯一性 · 多管家排查页面（无需合并）', () => {
  it('管家主管默认看到多管家排查视图，展示本组与跨组汇总且无任何合并操作入口', async () => {
    const wrapper = mountView(['customer:merge'])
    await flushPromises()

    expect(getMultiStewardSummary).toHaveBeenCalledTimes(1)
    expect(listMultiStewardCustomers).toHaveBeenCalledWith({
      page: 1,
      size: 20,
      keyword: undefined,
      minStewards: 2,
      crossTeamOnly: false,
    })
    expect(listMergeCandidates).not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="uniqueness-admin-tabs"]').exists()).toBe(false)

    expect(wrapper.text()).toContain('客户唯一性 · 多管家排查')
    expect(wrapper.text()).toContain('已加 3 位管家')
    expect(wrapper.text()).toContain('含跨组 1 位')
    expect(wrapper.text()).toContain('王丽（管家一组）')
    expect(wrapper.text()).toContain('周小洁（管家一组）')
    expect(wrapper.text()).toContain('李青（管家二组）')
    expect(wrapper.get('[data-testid="cross-team-summary-tag"]').text()).toBe('跨组仅汇总')
    expect(wrapper.find('[data-testid="steward-customer-btn-101"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="steward-customer-btn-102"]').exists()).toBe(true)

    // 不再暴露新建合并、扫描合并候选、确认合并按钮
    expect(wrapper.text()).not.toContain('扫描重复')
    expect(wrapper.text()).not.toContain('确认合并')
    expect(wrapper.text()).not.toContain('登记合并')
  })

  it('仅具备 customer:merge_reverse 的管理员可切到历史合并更正页签完成申请与审批', async () => {
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

    expect(wrapper.find('[data-testid="uniqueness-admin-tabs"]').exists()).toBe(true)
    await switchToHistoryTab(wrapper)

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

  it('历史无快照显示警示，并由有权限的管理员在历史页签提交人工完成说明', async () => {
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
    await switchToHistoryTab(wrapper)

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
