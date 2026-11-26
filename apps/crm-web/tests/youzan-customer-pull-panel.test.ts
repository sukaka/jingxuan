import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessage, ElMessageBox } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuthUser } from '../src/api/auth'
import type { CustomerOwnerOption } from '../src/api/customer'
import { listCustomerOwnerOptions } from '../src/api/customer'
import type {
  YouzanCustomerSyncItem,
  YouzanCustomerSyncRun,
  YouzanCustomerSyncStatus,
} from '../src/api/youzan'
import {
  getYouzanCustomerSyncStatus,
  listYouzanCustomerSyncRunItems,
  listYouzanCustomerSyncRuns,
  pullYouzanCustomers,
  resetYouzanCustomerSyncCursor,
} from '../src/api/youzan'
import { useAuthStore } from '../src/stores/auth'
import YouzanCustomerPullPanel from '../src/views/youzan/YouzanCustomerPullPanel.vue'

vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<typeof import('element-plus')>()
  return {
    ...actual,
    ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() },
    ElMessageBox: { confirm: vi.fn() },
  }
})

vi.mock('../src/api/youzan', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../src/api/youzan')>()),
  getYouzanCustomerSyncStatus: vi.fn(),
  pullYouzanCustomers: vi.fn(),
  resetYouzanCustomerSyncCursor: vi.fn(),
  listYouzanCustomerSyncRuns: vi.fn(),
  listYouzanCustomerSyncRunItems: vi.fn(),
}))

vi.mock('../src/api/customer', () => ({
  listCustomerOwnerOptions: vi.fn(),
}))

vi.mock('../src/api/dictionary', () => ({
  getDictionaryOptions: vi.fn().mockImplementation(async (dictCode: string) => {
    if (dictCode === 'customerSources') return [{ code: 'YOUZAN_MALL', label: '有赞商城', sortNo: 1 }]
    if (dictCode === 'customerChannels') return [{ code: 'MINI_PROGRAM', label: '小程序', sortNo: 1 }]
    return []
  }),
}))

const owners: CustomerOwnerOption[] = [
  { id: 9, displayName: '林管家', teamId: 1, teamName: '管家一组' },
  { id: 12, displayName: '陈管家', teamId: 2, teamName: '管家二组' },
]

const initialStatus: YouzanCustomerSyncStatus = {
  shopId: 'MOCK-SHOP-001',
  adapterName: 'MOCK_YOUZAN_CUSTOMER_DEV_ONLY',
  officialEnabled: false,
  scrmCursor: {
    sourceMode: 'SCRM_LIST',
    hasCursor: false,
    hasMore: false,
    pageNo: null,
    itemOffset: null,
    windowStart: null,
    windowEnd: null,
    lastAnchorAt: null,
    lastSyncedAt: null,
    totalImportedCount: 0,
  },
  orderBuyersCursor: {
    sourceMode: 'ORDER_BUYERS',
    hasCursor: true,
    hasMore: true,
    pageNo: 2,
    itemOffset: 5,
    windowStart: '2026-10-01T00:00:00Z',
    windowEnd: '2026-11-26T00:00:00Z',
    lastAnchorAt: null,
    lastSyncedAt: '2026-11-26T08:00:00Z',
    totalImportedCount: 15,
  },
}

const createdItem: YouzanCustomerSyncItem = {
  id: 1001,
  runId: 501,
  shopId: 'MOCK-SHOP-001',
  externalCustomerId: 'yz_open_001',
  outcome: 'CREATED',
  displayNameMasked: '王*士',
  mobileMasked: '138****0001',
  crmCustomerId: 88,
  crmCustomerNo: 'C0000000088',
  conflictCustomerIds: [],
  conflictCustomerNos: [],
  detailMasked: '已自动建档并建立强关联',
  createdAt: '2026-11-26T09:00:00Z',
}

const conflictItem: YouzanCustomerSyncItem = {
  id: 1002,
  runId: 501,
  shopId: 'MOCK-SHOP-001',
  externalCustomerId: 'yz_open_conflict_12',
  outcome: 'MOBILE_CONFLICT',
  displayNameMasked: '李*姐',
  mobileMasked: '139****0012',
  crmCustomerId: null,
  crmCustomerNo: null,
  conflictCustomerIds: [12],
  conflictCustomerNos: ['C0000000012'],
  detailMasked: '手机号命中已有 CRM 档案，保留老客户待人工确认',
  createdAt: '2026-11-26T09:00:01Z',
}

function makeRun(overrides: Partial<YouzanCustomerSyncRun> = {}): YouzanCustomerSyncRun {
  return {
    id: 501,
    shopId: 'MOCK-SHOP-001',
    sourceMode: 'SCRM_LIST',
    pullMode: 'BATCH',
    requestedLimit: 10,
    ownerStaffId: 9,
    ownerName: '林管家',
    adapterName: 'MOCK_YOUZAN_CUSTOMER_DEV_ONLY',
    status: 'SUCCEEDED',
    fetchedCount: 10,
    createdCount: 8,
    skippedExistingCount: 2,
    mobileConflictCount: 0,
    failedCount: 0,
    hasMore: true,
    warnings: [],
    errorMasked: null,
    startedAt: '2026-11-26T09:00:00Z',
    finishedAt: '2026-11-26T09:00:02Z',
    items: [createdItem],
    ...overrides,
  }
}

function makeUser(permissions: string[], userType = 'EMPLOYEE'): AuthUser {
  return {
    userId: 1,
    username: 'youzan-admin',
    displayName: '测试管理员',
    userType,
    roles: ['ADMIN'],
    permissions,
  }
}

const wrappers: VueWrapper[] = []

function mountPanel(permissions: string[], shopId = 'MOCK-SHOP-001', userType = 'EMPLOYEE') {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = makeUser(permissions, userType)
  auth.initialized = true
  const wrapper = mount(YouzanCustomerPullPanel, {
    props: { shopId },
    global: { plugins: [ElementPlus, pinia] },
    attachTo: document.body,
  })
  wrappers.push(wrapper)
  return wrapper
}

function component(wrapper: VueWrapper, testId: string): VueWrapper {
  return wrapper.getComponent(`[data-testid="${testId}"]`) as VueWrapper
}

function selectOwner(wrapper: VueWrapper, ownerId: number) {
  component(wrapper, 'yz-pull-owner').vm.$emit('update:modelValue', ownerId)
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(listCustomerOwnerOptions).mockResolvedValue(owners)
  vi.mocked(getYouzanCustomerSyncStatus).mockResolvedValue(initialStatus)
  vi.mocked(listYouzanCustomerSyncRuns).mockResolvedValue({ items: [], total: 0, page: 1, size: 10 })
  vi.mocked(listYouzanCustomerSyncRunItems).mockResolvedValue({ items: [createdItem], total: 1, page: 1, size: 20 })
  vi.mocked(pullYouzanCustomers).mockResolvedValue(makeRun())
  vi.mocked(resetYouzanCustomerSyncCursor).mockResolvedValue(initialStatus)
  vi.mocked(ElMessageBox.confirm).mockResolvedValue({ value: 'confirm', action: 'confirm' } as never)
})

afterEach(() => {
  while (wrappers.length) wrappers.pop()?.unmount()
  document.body.innerHTML = ''
})

describe('YouzanCustomerPullPanel 有赞客户拉取与建档', () => {
  it('展示通道状态、断点续拉防重说明，并支持 10 / 20 / 自定义条数分批拉取', async () => {
    vi.mocked(getYouzanCustomerSyncStatus)
      .mockResolvedValueOnce(initialStatus)
      .mockResolvedValueOnce({
        ...initialStatus,
        scrmCursor: {
          ...initialStatus.scrmCursor,
          hasCursor: true,
          hasMore: true,
          pageNo: 1,
          itemOffset: 10,
          lastSyncedAt: '2026-11-26T09:00:02Z',
          totalImportedCount: 8,
        },
      })
      .mockResolvedValue({
        ...initialStatus,
        scrmCursor: {
          ...initialStatus.scrmCursor,
          hasCursor: true,
          hasMore: true,
          pageNo: 2,
          itemOffset: 10,
          lastSyncedAt: '2026-11-26T09:01:02Z',
          totalImportedCount: 28,
        },
      })
    vi.mocked(pullYouzanCustomers)
      .mockResolvedValueOnce(makeRun({ id: 501, requestedLimit: 10, fetchedCount: 10, createdCount: 8, skippedExistingCount: 2, hasMore: true }))
      .mockResolvedValueOnce(makeRun({ id: 502, requestedLimit: 20, fetchedCount: 20, createdCount: 20, skippedExistingCount: 0, hasMore: true }))
      .mockResolvedValueOnce(makeRun({ id: 503, requestedLimit: 15, fetchedCount: 15, createdCount: 15, skippedExistingCount: 0, hasMore: false }))

    const wrapper = mountPanel(['integration:youzan:manage', 'customer:write'])
    await flushPromises()

    expect(wrapper.get('[data-testid="yz-customer-channel-pill"]').text()).toBe('本地模拟演示')
    expect(wrapper.get('[data-testid="yz-customer-cursor-note"]').text()).toContain('这次拉 10 条，下次自动接着第 11 条继续拉，不重复建档')

    // 未选专属管家时拦截
    await wrapper.get('[data-testid="yz-customer-pull-batch"]').trigger('click')
    expect(pullYouzanCustomers).not.toHaveBeenCalled()
    expect(ElMessage.warning).toHaveBeenCalledWith(expect.stringContaining('目标专属管家'))

    // 选择专属管家、来源与渠道，按 10 条拉取
    selectOwner(wrapper, 9)
    component(wrapper, 'yz-pull-source-code').vm.$emit('update:modelValue', 'YOUZAN_MALL')
    component(wrapper, 'yz-pull-channel-code').vm.$emit('update:modelValue', 'MINI_PROGRAM')
    await wrapper.get('[data-testid="yz-customer-preset-10"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="yz-customer-pull-batch"]').trigger('click')
    await flushPromises()

    expect(pullYouzanCustomers).toHaveBeenNthCalledWith(1, {
      shopId: 'MOCK-SHOP-001',
      sourceMode: 'SCRM_LIST',
      pullMode: 'BATCH',
      limit: 10,
      ownerStaffId: 9,
      sourceCode: 'YOUZAN_MALL',
      channelCode: 'MINI_PROGRAM',
      enrichProfile: true,
    }, expect.stringMatching(/^youzan\.customer\.pull\.batch:/))
    expect(wrapper.emitted('imported')).toHaveLength(1)
    expect(wrapper.get('[data-testid="yz-customer-pull-batch"]').text()).toBe('继续拉取 10 条')
    expect(wrapper.get('[data-testid="yz-customer-cursor-card"]').text()).toContain('第 1 页 · 偏移 10')

    // 切换 20 条继续拉
    await wrapper.get('[data-testid="yz-customer-preset-20"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="yz-customer-pull-batch"]').trigger('click')
    await flushPromises()
    expect(pullYouzanCustomers).toHaveBeenNthCalledWith(2, expect.objectContaining({
      pullMode: 'BATCH',
      limit: 20,
      ownerStaffId: 9,
    }), expect.any(String))

    // 自定义输入 15 条并切换到 ORDER_BUYERS
    component(wrapper, 'yz-source-mode').vm.$emit('update:modelValue', 'ORDER_BUYERS')
    component(wrapper, 'yz-customer-limit').vm.$emit('update:modelValue', 15)
    component(wrapper, 'yz-pull-enrich').vm.$emit('update:modelValue', false)
    await flushPromises()
    await wrapper.get('[data-testid="yz-customer-pull-batch"]').trigger('click')
    await flushPromises()

    expect(pullYouzanCustomers).toHaveBeenNthCalledWith(3, {
      shopId: 'MOCK-SHOP-001',
      sourceMode: 'ORDER_BUYERS',
      pullMode: 'BATCH',
      limit: 15,
      ownerStaffId: 9,
      sourceCode: 'YOUZAN_MALL',
      channelCode: 'MINI_PROGRAM',
      enrichProfile: false,
    }, expect.any(String))
    const keys = vi.mocked(pullYouzanCustomers).mock.calls.map((call) => call[1])
    expect(new Set(keys).size).toBe(3)
  })

  it('全部拉取按每批 200 条自动续拉直至 hasMore=false，每批独立 Idempotency-Key 并累计汇总', async () => {
    vi.mocked(pullYouzanCustomers)
      .mockResolvedValueOnce(makeRun({
        id: 601,
        pullMode: 'ALL',
        requestedLimit: 200,
        fetchedCount: 200,
        createdCount: 180,
        skippedExistingCount: 15,
        mobileConflictCount: 4,
        failedCount: 1,
        hasMore: true,
        items: [createdItem],
      }))
      .mockResolvedValueOnce(makeRun({
        id: 602,
        pullMode: 'ALL',
        requestedLimit: 200,
        fetchedCount: 50,
        createdCount: 40,
        skippedExistingCount: 10,
        mobileConflictCount: 0,
        failedCount: 0,
        hasMore: false,
        items: [createdItem],
      }))

    const wrapper = mountPanel(['integration:youzan:manage', 'customer:write'])
    await flushPromises()
    selectOwner(wrapper, 9)
    await flushPromises()

    await wrapper.get('[data-testid="yz-customer-pull-all"]').trigger('click')
    await flushPromises()

    expect(pullYouzanCustomers).toHaveBeenCalledTimes(2)
    expect(pullYouzanCustomers).toHaveBeenNthCalledWith(1, expect.objectContaining({ pullMode: 'ALL', limit: 200 }), expect.any(String))
    expect(pullYouzanCustomers).toHaveBeenNthCalledWith(2, expect.objectContaining({ pullMode: 'ALL', limit: 200 }), expect.any(String))
    const firstKey = vi.mocked(pullYouzanCustomers).mock.calls[0]?.[1]
    const secondKey = vi.mocked(pullYouzanCustomers).mock.calls[1]?.[1]
    expect(firstKey).not.toBe(secondKey)

    const progress = wrapper.get('[data-testid="yz-pull-all-progress"]').text()
    expect(progress).toContain('已执行 2 批')
    expect(progress).toContain('250')
    expect(progress).toContain('220')
    expect(progress).toContain('25')
    expect(progress).toContain('4')
    expect(wrapper.emitted('imported')).toHaveLength(1)
  })

  it('全部拉取中途点击「停止拉取」后完成当前批次即停止后续请求', async () => {
    let resolveFirst!: (value: YouzanCustomerSyncRun) => void
    const firstBatchPromise = new Promise<YouzanCustomerSyncRun>((resolve) => {
      resolveFirst = resolve
    })
    vi.mocked(pullYouzanCustomers).mockReturnValueOnce(firstBatchPromise)

    const wrapper = mountPanel(['integration:youzan:manage', 'customer:write'])
    await flushPromises()
    selectOwner(wrapper, 9)
    await flushPromises()

    await wrapper.get('[data-testid="yz-customer-pull-all"]').trigger('click')
    await flushPromises()

    const stopButton = wrapper.get('[data-testid="yz-customer-pull-stop"]')
    expect(stopButton.text()).toBe('停止拉取')
    await stopButton.trigger('click')
    await flushPromises()

    resolveFirst(makeRun({
      id: 701,
      pullMode: 'ALL',
      fetchedCount: 200,
      createdCount: 150,
      skippedExistingCount: 50,
      hasMore: true,
      items: [createdItem],
    }))
    await flushPromises()

    expect(pullYouzanCustomers).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[data-testid="yz-pull-all-progress"]').text()).toContain('已手动停止')
    expect(wrapper.get('[data-testid="yz-pull-all-progress"]').text()).toContain('已执行 1 批')
    expect(ElMessage.info).toHaveBeenCalledWith(expect.stringContaining('已停止全部拉取'))
  })

  it('手机号冲突展示命中老客户编号，点击「去人工链接」触发 link-external 事件并支持历史批次筛选明细', async () => {
    const conflictRun = makeRun({
      id: 801,
      fetchedCount: 2,
      createdCount: 1,
      skippedExistingCount: 0,
      mobileConflictCount: 1,
      failedCount: 0,
      hasMore: false,
      items: [createdItem, conflictItem],
    })
    vi.mocked(listYouzanCustomerSyncRuns).mockResolvedValue({
      items: [conflictRun],
      total: 1,
      page: 1,
      size: 10,
    })
    vi.mocked(listYouzanCustomerSyncRunItems)
      .mockResolvedValueOnce({ items: [createdItem, conflictItem], total: 2, page: 1, size: 20 })
      .mockResolvedValueOnce({ items: [conflictItem], total: 1, page: 1, size: 20 })

    const wrapper = mountPanel(['integration:youzan:manage', 'customer:write'])
    await flushPromises()

    await wrapper.get('[data-testid="yz-run-view-items"]').trigger('click')
    await flushPromises()

    expect(listYouzanCustomerSyncRunItems).toHaveBeenNthCalledWith(1, 801, { outcome: undefined, page: 1, size: 20 })
    expect(wrapper.get('[data-testid="yz-item-conflict-nos"]').text()).toContain('C0000000012')

    // 筛选 MOBILE_CONFLICT
    component(wrapper, 'yz-item-outcome-filter').vm.$emit('update:modelValue', 'MOBILE_CONFLICT')
    component(wrapper, 'yz-item-outcome-filter').vm.$emit('change', 'MOBILE_CONFLICT')
    await flushPromises()
    expect(listYouzanCustomerSyncRunItems).toHaveBeenNthCalledWith(2, 801, { outcome: 'MOBILE_CONFLICT', page: 1, size: 20 })

    // 点击去人工链接
    await wrapper.get('[data-testid="yz-item-link-external"]').trigger('click')
    expect(wrapper.emitted('link-external')).toEqual([['yz_open_conflict_12']])
  })

  it('重置游标需二次确认：取消不发请求，确认后调用 cursor/reset 并更新游标状态', async () => {
    vi.mocked(ElMessageBox.confirm)
      .mockRejectedValueOnce('cancel')
      .mockResolvedValueOnce({ value: 'confirm', action: 'confirm' } as never)
    vi.mocked(getYouzanCustomerSyncStatus).mockResolvedValue({
      ...initialStatus,
      officialEnabled: true,
      adapterName: 'OFFICIAL_YOUZAN_CUSTOMER',
      scrmCursor: {
        ...initialStatus.scrmCursor,
        hasCursor: true,
        hasMore: true,
        pageNo: 4,
        itemOffset: 12,
        totalImportedCount: 72,
      },
    })
    vi.mocked(resetYouzanCustomerSyncCursor).mockResolvedValue({
      ...initialStatus,
      officialEnabled: true,
      adapterName: 'OFFICIAL_YOUZAN_CUSTOMER',
      scrmCursor: {
        ...initialStatus.scrmCursor,
        hasCursor: false,
        hasMore: false,
        pageNo: null,
        itemOffset: null,
        totalImportedCount: 72,
      },
    })

    const wrapper = mountPanel(['integration:youzan:manage', 'customer:write'])
    await flushPromises()

    expect(wrapper.get('[data-testid="yz-customer-channel-pill"]').text()).toBe('真实有赞通道')
    expect(wrapper.get('[data-testid="yz-customer-cursor-card"]').text()).toContain('第 4 页 · 偏移 12')

    // 第一次点击取消
    await wrapper.get('[data-testid="yz-customer-reset-cursor"]').trigger('click')
    await flushPromises()
    expect(resetYouzanCustomerSyncCursor).not.toHaveBeenCalled()

    // 第二次点击确认
    await wrapper.get('[data-testid="yz-customer-reset-cursor"]').trigger('click')
    await flushPromises()
    expect(resetYouzanCustomerSyncCursor).toHaveBeenCalledWith({
      shopId: 'MOCK-SHOP-001',
      sourceMode: 'SCRM_LIST',
    })
    expect(wrapper.get('[data-testid="yz-customer-cursor-card"]').text()).toContain('初始位置（从头拉取）')
    expect(ElMessage.success).toHaveBeenCalledWith(expect.stringContaining('同步游标已重置'))
  })

  it('缺少 customer:write 时隐藏执行拉取与重置按钮且不请求管家选项；无管理权限时拦截访问', async () => {
    const readonlyWrapper = mountPanel(['integration:youzan:manage'])
    await flushPromises()

    expect(readonlyWrapper.find('[data-testid="yz-customer-pull-readonly-note"]').exists()).toBe(true)
    expect(readonlyWrapper.find('[data-testid="yz-customer-config-card"]').exists()).toBe(false)
    expect(readonlyWrapper.find('[data-testid="yz-customer-pull-batch"]').exists()).toBe(false)
    expect(readonlyWrapper.find('[data-testid="yz-customer-pull-all"]').exists()).toBe(false)
    expect(readonlyWrapper.find('[data-testid="yz-customer-reset-cursor"]').exists()).toBe(false)
    expect(listCustomerOwnerOptions).not.toHaveBeenCalled()
    expect(getYouzanCustomerSyncStatus).toHaveBeenCalledTimes(1)

    vi.clearAllMocks()
    const forbiddenWrapper = mountPanel(['customer:write'])
    await flushPromises()

    expect(forbiddenWrapper.text()).toContain('当前账号暂无有赞接入管理权限')
    expect(getYouzanCustomerSyncStatus).not.toHaveBeenCalled()
    expect(listYouzanCustomerSyncRuns).not.toHaveBeenCalled()
    expect(listCustomerOwnerOptions).not.toHaveBeenCalled()
  })

  it('一轮扫完（hasCursor=true, hasMore=false, pageNo/itemOffset=null, lastAnchorAt 非空）时展示「本轮已完成（下次自动增量）」', async () => {
    vi.mocked(getYouzanCustomerSyncStatus).mockResolvedValue({
      ...initialStatus,
      scrmCursor: {
        sourceMode: 'SCRM_LIST',
        hasCursor: true,
        hasMore: false,
        pageNo: null,
        itemOffset: null,
        windowStart: null,
        windowEnd: null,
        lastAnchorAt: '2026-11-26T09:30:00Z',
        lastSyncedAt: '2026-11-26T09:30:05Z',
        totalImportedCount: 35,
      },
    })

    const wrapper = mountPanel(['integration:youzan:manage', 'customer:write'])
    await flushPromises()

    const cursorCardText = wrapper.get('[data-testid="yz-customer-cursor-card"]').text()
    expect(cursorCardText).toContain('本轮已完成（下次自动增量）')
    expect(cursorCardText).toContain('增量锚点：')
    expect(cursorCardText).not.toContain('第 1 页 · 偏移 0')
  })

  it('全部拉取实时响应式更新批次数，且批次返回 FAILED（failedCount=0）时标记异常不误报完成', async () => {
    let resolveSecond!: (value: YouzanCustomerSyncRun) => void
    const secondBatchPromise = new Promise<YouzanCustomerSyncRun>((resolve) => {
      resolveSecond = resolve
    })
    vi.mocked(pullYouzanCustomers)
      .mockResolvedValueOnce(makeRun({
        id: 811,
        pullMode: 'ALL',
        fetchedCount: 200,
        createdCount: 120,
        skippedExistingCount: 80,
        failedCount: 0,
        hasMore: true,
        items: [createdItem],
      }))
      .mockReturnValueOnce(secondBatchPromise)

    const wrapper = mountPanel(['integration:youzan:manage', 'customer:write'])
    await flushPromises()
    selectOwner(wrapper, 9)
    await flushPromises()

    await wrapper.get('[data-testid="yz-customer-pull-all"]').trigger('click')
    await flushPromises()

    // 第 1 批已完成、第 2 批仍在途时，响应式进度横幅已实时展示第 1 批汇总
    const liveProgress = wrapper.get('[data-testid="yz-pull-all-progress"]').text()
    expect(liveProgress).toContain('正在执行全部拉取')
    expect(liveProgress).toContain('已执行 1 批')
    expect(liveProgress).toContain('120')

    // 第 2 批返回 status='FAILED' 且 failedCount=0
    resolveSecond(makeRun({
      id: 812,
      pullMode: 'ALL',
      status: 'FAILED',
      fetchedCount: 0,
      createdCount: 0,
      skippedExistingCount: 0,
      failedCount: 0,
      hasMore: true,
      errorMasked: '有赞接口限流（已脱敏）',
      items: [],
    }))
    await flushPromises()

    const finalProgress = wrapper.get('[data-testid="yz-pull-all-progress"]').text()
    expect(finalProgress).toContain('全部拉取中途异常')
    expect(finalProgress).toContain('有赞接口限流（已脱敏）')
    expect(finalProgress).not.toContain('全部拉取已完成')
    expect(ElMessage.error).toHaveBeenCalledWith(expect.stringContaining('全部拉取异常终止'))
    expect(ElMessage.success).not.toHaveBeenCalled()
  })

  it('切换店铺或历史批次时忽略过期的慢响应，不覆盖新店铺/新批次数据', async () => {
    let resolveOldStatus!: (value: YouzanCustomerSyncStatus) => void
    const oldStatusPromise = new Promise<YouzanCustomerSyncStatus>((resolve) => {
      resolveOldStatus = resolve
    })
    let resolveOldRunItems!: (value: { items: YouzanCustomerSyncItem[]; total: number; page: number; size: number }) => void
    const oldRunItemsPromise = new Promise<{ items: YouzanCustomerSyncItem[]; total: number; page: number; size: number }>((resolve) => {
      resolveOldRunItems = resolve
    })

    const realShopStatus: YouzanCustomerSyncStatus = {
      ...initialStatus,
      shopId: '40880001',
      adapterName: 'OFFICIAL_YOUZAN_CUSTOMER',
      officialEnabled: true,
    }
    vi.mocked(getYouzanCustomerSyncStatus).mockImplementation((shopId) => (
      shopId === 'MOCK-SHOP-001' ? oldStatusPromise : Promise.resolve(realShopStatus)
    ))
    const runA = makeRun({ id: 901, shopId: '40880001', items: undefined })
    const runB = makeRun({ id: 902, shopId: '40880001', items: undefined })
    vi.mocked(listYouzanCustomerSyncRuns).mockImplementation(async ({ shopId }) => (
      shopId === '40880001'
        ? { items: [runA, runB], total: 2, page: 1, size: 10 }
        : { items: [], total: 0, page: 1, size: 10 }
    ))
    vi.mocked(listYouzanCustomerSyncRunItems).mockImplementation((runId) => (
      runId === 901
        ? oldRunItemsPromise
        : Promise.resolve({ items: [conflictItem], total: 1, page: 1, size: 20 })
    ))

    const wrapper = mountPanel(['integration:youzan:manage', 'customer:write'], 'MOCK-SHOP-001')
    await wrapper.setProps({ shopId: '40880001' })
    await flushPromises()

    // 旧店铺慢响应晚到，不能覆盖新店铺 40880001
    resolveOldStatus(initialStatus)
    await flushPromises()
    expect(wrapper.get('[data-testid="yz-customer-adapter-meta"]').text()).toContain('40880001')
    expect(wrapper.get('[data-testid="yz-customer-channel-pill"]').text()).toBe('真实有赞通道')

    // 快速连续点两个历史批次，批次 901 慢响应晚到不能覆盖批次 902
    const viewButtons = wrapper.findAll('[data-testid="yz-run-view-items"]')
    expect(viewButtons).toHaveLength(2)
    await viewButtons[0]!.trigger('click')
    await viewButtons[1]!.trigger('click')
    await flushPromises()

    resolveOldRunItems({ items: [createdItem], total: 1, page: 1, size: 20 })
    await flushPromises()

    expect(wrapper.get('[data-testid="yz-run-items-section"]').text()).toContain('批次 #902')
    expect(wrapper.get('[data-testid="yz-run-items-section"]').text()).toContain('yz_open_conflict_12')
    expect(wrapper.get('[data-testid="yz-run-items-section"]').text()).not.toContain('yz_open_001')
  })

  it('单批内联返回 50 条明细时首页按每页 20 条对齐分页器不溢出展示', async () => {
    const fiftyItems: YouzanCustomerSyncItem[] = Array.from({ length: 50 }, (_, index) => ({
      ...createdItem,
      id: 2000 + index,
      externalCustomerId: `yz_open_inline_${index + 1}`,
      crmCustomerNo: `C0000001${String(index + 1).padStart(3, '0')}`,
    }))
    vi.mocked(pullYouzanCustomers).mockResolvedValue(makeRun({
      id: 950,
      requestedLimit: 50,
      fetchedCount: 50,
      createdCount: 50,
      skippedExistingCount: 0,
      hasMore: false,
      items: fiftyItems,
    }))

    const wrapper = mountPanel(['integration:youzan:manage', 'customer:write'])
    await flushPromises()
    selectOwner(wrapper, 9)
    await wrapper.get('[data-testid="yz-customer-preset-50"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="yz-customer-pull-batch"]').trigger('click')
    await flushPromises()

    const itemsSection = wrapper.get('[data-testid="yz-run-items-section"]')
    expect(itemsSection.findAll('.el-table__body-wrapper tbody tr')).toHaveLength(20)
    expect(itemsSection.text()).toContain('yz_open_inline_1')
    expect(itemsSection.text()).not.toContain('yz_open_inline_21')
    expect(listYouzanCustomerSyncRunItems).not.toHaveBeenCalled()
  })
})
