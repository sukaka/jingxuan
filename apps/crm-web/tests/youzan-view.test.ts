import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessage } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuthUser } from '../src/api/auth'
import type { CustomerDetail, CustomerListItem } from '../src/api/customer'
import { getCustomer, listCustomerOwnerOptions, listCustomers } from '../src/api/customer'
import type { Sku } from '../src/api/catalog'
import { listSkus } from '../src/api/catalog'
import type { YouzanCustomerLink, YouzanOrderView, YouzanSkuCandidate, YouzanSyncIssue } from '../src/api/youzan'
import {
  assignYouzanOrderSteward,
  batchRetryYouzanSyncIssues,
  deleteYouzanStewardAlias,
  getYouzanCustomerSyncStatus,
  getYouzanReconcileReport,
  listYouzanCustomerLinks,
  listYouzanCustomerSyncRunItems,
  listYouzanCustomerSyncRuns,
  listYouzanOrders,
  listYouzanShops,
  listYouzanSkuCandidates,
  listYouzanSkuMappings,
  listYouzanStewardAliases,
  listYouzanStewardOptions,
  listYouzanSyncIssues,
  pullYouzanCustomers,
  pullYouzanOrderByTid,
  reconcileYouzanOrders,
  resetYouzanCustomerSyncCursor,
  retryYouzanSyncIssue,
  saveYouzanCustomerLink,
  saveYouzanSkuMapping,
  saveYouzanStewardAlias,
  syncYouzanOrders,
} from '../src/api/youzan'
import { getOrder } from '../src/api/trading'
import type { CrmOrder } from '../src/api/trading'
import { useAuthStore } from '../src/stores/auth'
import { ApiError } from '../src/types/api'
import YouzanOrdersView from '../src/views/YouzanOrdersView.vue'

vi.mock('../src/api/trading', () => ({
  getOrder: vi.fn(),
}))

vi.mock('../src/api/youzan', () => ({
  listYouzanOrders: vi.fn(),
  pullYouzanOrderByTid: vi.fn(),
  assignYouzanOrderSteward: vi.fn(),
  listYouzanStewardOptions: vi.fn(),
  listYouzanStewardAliases: vi.fn(),
  saveYouzanStewardAlias: vi.fn(),
  deleteYouzanStewardAlias: vi.fn(),
  listYouzanShops: vi.fn(),
  syncYouzanOrders: vi.fn(),
  reconcileYouzanOrders: vi.fn(),
  getYouzanReconcileReport: vi.fn(),
  listYouzanSyncIssues: vi.fn(),
  retryYouzanSyncIssue: vi.fn(),
  batchRetryYouzanSyncIssues: vi.fn(),
  listYouzanSkuCandidates: vi.fn(),
  listYouzanSkuMappings: vi.fn(),
  saveYouzanSkuMapping: vi.fn(),
  listYouzanCustomerLinks: vi.fn(),
  saveYouzanCustomerLink: vi.fn(),
  getYouzanCustomerSyncStatus: vi.fn(),
  pullYouzanCustomers: vi.fn(),
  resetYouzanCustomerSyncCursor: vi.fn(),
  listYouzanCustomerSyncRuns: vi.fn(),
  listYouzanCustomerSyncRunItems: vi.fn(),
}))
vi.mock('../src/api/catalog', () => ({ listSkus: vi.fn(), listInstitutions: vi.fn().mockResolvedValue({ items: [], total: 0, page: 1, size: 100 }), listProjects: vi.fn().mockResolvedValue({ items: [], total: 0, page: 1, size: 100 }) }))
vi.mock('../src/api/customer', () => ({ listCustomers: vi.fn(), getCustomer: vi.fn(), listCustomerOwnerOptions: vi.fn() }))
vi.mock('../src/api/dictionary', () => ({ getDictionaryOptions: vi.fn().mockResolvedValue([]) }))

const order: YouzanOrderView = {
  mapId: 1,
  shopId: 'MOCK-SHOP-001',
  externalOrderNo: 'YZ-MOCK-PAID-001',
  orderId: 71,
  status: 'PROCESSED',
  paidFen: 362200,
  sourceSystem: 'YOUZAN',
  externalShopId: 'MOCK-SHOP-001',
  eventId: 'YZ-EVENT-PAID-001',
  mappingStatus: 'PROCESSED',
  orderStatus: 'PAID',
  sourceUpdatedAt: '2026-10-24T03:00:00Z',
  externalAmountFen: 362200,
  verificationMethod: 'MANUAL_REVIEW',
  payloadMaskedJson: '{"event_id":"YZ-MOCK-PAID-001","mobile":"***"}',
  errorReason: null,
}

const internalOrder: CrmOrder = {
  id: 71,
  orderNo: 'J000000000071',
  customerId: 42,
  customerName: '测试客户',
  soldByUserId: 9,
  ownerAtSaleId: 9,
  externalOrderNo: 'YZ-MOCK-PAID-001',
  sourceSystem: 'YOUZAN',
  orderTypeCode: 'REGULAR',
  campaignId: 1,
  campaignBatchId: 1,
  status: 'PAID',
  receiverType: 'PLATFORM',
  listAmountFen: 362200,
  discountAmountFen: 0,
  payableFen: 362200,
  paidFen: 362200,
  refundedFen: 0,
  orderedAt: '2026-10-24T03:00:00Z',
  paidAt: '2026-10-24T03:00:00Z',
  createdAt: '2026-10-24T03:00:00Z',
  version: 2,
}

const issue: YouzanSyncIssue = {
  id: 801,
  eventId: 901,
  issueType: 'MAPPING_FAILED',
  status: 'OPEN',
  detailMasked: '外部 SKU 未映射',
  attempts: 1,
  lastError: '外部 SKU 未映射',
  nextRetryAt: null,
  createdAt: '2026-10-24T01:00:00Z',
  resolvedAt: null,
  resolvedBy: null,
  version: 2,
}

const skuMapping = {
  id: 301,
  shopId: 'MOCK-SHOP-001',
  externalSkuId: 'YZ-SKU-301',
  crmSkuId: 301,
  status: 'ACTIVE',
  createdAt: '2026-10-24T01:00:00Z',
  version: 2,
}

const customerLink: YouzanCustomerLink = {
  id: 401,
  shopId: 'MOCK-SHOP-001',
  externalCustomerId: 'YZ-CUSTOMER-1',
  customerId: 42,
  linkType: 'MANUAL',
  confidence: 'HIGH',
  reason: '人工核对订单与CRM档案',
  createdBy: 1,
  createdAt: '2026-10-24T01:00:00Z',
  version: 1,
}

const sku: Sku = {
  id: 301,
  skuCode: 'SKU-TEST',
  packageVersionId: 201,
  packageId: 101,
  packageName: '测试套餐',
  versionNo: 1,
  selectionMode: 'USE_N',
  specName: '3次',
  salePriceFen: 181100,
  status: 'ACTIVE',
  onSaleAt: null,
  offSaleAt: null,
  createdAt: '2026-10-24T01:00:00Z',
  version: 0,
}

const customer: CustomerListItem = {
  id: 42,
  customerNo: 'C0000000042',
  displayName: '测试客户',
  nickname: null,
  mobileMasked: '138****0000',
  cityCode: null,
  customerStatus: 'ACTIVE',
  tierCode: null,
  intentLevel: null,
  ownerStaffId: 9,
  ownerName: '测试管家',
  teamId: 1,
  pointsCached: 0,
  lastFollowupAt: null,
  createdAt: '2026-10-24T01:00:00Z',
  version: 0,
}

const customerDetail: CustomerDetail = {
  ...customer,
  sourceCode: null,
  channelCode: null,
  spendingLevel: null,
  birthday: null,
  attentionProjects: null,
  blockerNote: null,
  remark: null,
  remarkName: null,
  firstAddedAt: null,
  firstOrderAt: null,
  tagCodes: [],
  identities: [],
  deleted: false,
}

function makeUser(permissions: string[]): AuthUser {
  return {
    userId: 1,
    username: 'youzan-test-admin',
    displayName: '有赞测试管理员',
    userType: 'EMPLOYEE',
    roles: ['ADMIN'],
    permissions,
  }
}

const wrappers: VueWrapper[] = []
afterEach(() => {
  while (wrappers.length) wrappers.pop()?.unmount()
  document.body.innerHTML = ''
  window.localStorage.clear()
  window.history.replaceState({}, '', '/')
  vi.restoreAllMocks()
})

function mountView(permissions: string[]) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = makeUser(permissions)
  auth.initialized = true
  const wrapper = mount(YouzanOrdersView, {
    global: { plugins: [ElementPlus, pinia] },
    attachTo: document.body,
  })
  wrappers.push(wrapper)
  return wrapper
}

function component(wrapper: VueWrapper, testId: string): VueWrapper {
  return wrapper.getComponent(`[data-testid="${testId}"]`) as VueWrapper
}

async function openTab(wrapper: VueWrapper, label: string) {
  const tab = wrapper.findAll('.el-tabs__item').find((item) => item.text() === label)
  expect(tab).toBeDefined()
  await tab?.trigger('click')
  await flushPromises()
}

describe('YouzanOrdersView 模拟同步管理', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    window.localStorage.clear()
    vi.mocked(listYouzanOrders).mockResolvedValue({ items: [order], total: 1, page: 1, size: 20 })
    vi.mocked(listYouzanStewardOptions).mockResolvedValue([{ id: 9, displayName: '测试管家', teamId: 1, teamName: '管家一组' }])
    vi.mocked(listYouzanStewardAliases).mockResolvedValue([])
    vi.mocked(pullYouzanOrderByTid).mockResolvedValue(order)
    vi.mocked(assignYouzanOrderSteward).mockResolvedValue({ ...order, assignedStewardId: 9, assignedStewardName: '测试管家', assignmentStatus: 'ASSIGNED', assignmentSource: 'MANUAL' })
    vi.mocked(saveYouzanStewardAlias).mockResolvedValue({
      alias: { id: 1, aliasName: '紫紫模拟', aliasKey: '紫紫模拟', stewardUserId: 9, stewardName: '测试管家', teamId: 1, teamName: '管家一组', status: 'ACTIVE', remark: null, createdAt: '2026-11-27T07:00:00Z', updatedAt: '2026-11-27T07:00:00Z', version: 0 },
      backfilledOrderCount: 1,
    })
    vi.mocked(deleteYouzanStewardAlias).mockResolvedValue(undefined)
    vi.mocked(listYouzanShops).mockResolvedValue([])
    vi.mocked(getYouzanReconcileReport).mockResolvedValue({ shopId: 'MOCK-SHOP-001', totalEvents: 2, internalOrders: 1, openIssues: 1, lastSyncAt: '2026-10-24T02:00:00Z', todayEvents: 2, campaignOrders: 1 })
    vi.mocked(listYouzanSyncIssues).mockResolvedValue({ items: [issue], total: 1, page: 1, size: 20 })
    vi.mocked(listYouzanSkuCandidates).mockResolvedValue([])
    vi.mocked(listYouzanSkuMappings).mockResolvedValue([skuMapping])
    vi.mocked(listYouzanCustomerLinks).mockResolvedValue([customerLink])
    vi.mocked(syncYouzanOrders).mockResolvedValue({ received: 2, processed: 1, duplicates: 0, issues: 1, nextCursor: '2', hasMore: false })
    vi.mocked(reconcileYouzanOrders).mockResolvedValue({ received: 2, processed: 2, duplicates: 0, issues: 0, nextCursor: '2', hasMore: false })
    vi.mocked(retryYouzanSyncIssue).mockResolvedValue({ id: 901, eventId: 'YZ-MOCK-PAID-001', eventType: 'ORDER_PAID', externalOrderNo: 'YZ-MOCK-PAID-001', processStatus: 'PROCESSED', processAttempts: 2, lastError: null, orderId: 71, receivedAt: '2026-10-24T01:00:00Z', processedAt: '2026-10-24T02:00:00Z', duplicate: true })
    vi.mocked(batchRetryYouzanSyncIssues).mockResolvedValue({ shopId: 'MOCK-SHOP-001', externalSkuId: null, matched: 0, attempted: 0, processed: 0, advanced: 0, stillFailed: 0, skipped: 0, remaining: 0 })
    vi.mocked(saveYouzanSkuMapping).mockResolvedValue(skuMapping)
    vi.mocked(saveYouzanCustomerLink).mockResolvedValue(customerLink)
    vi.mocked(getYouzanCustomerSyncStatus).mockResolvedValue({
      shopId: 'MOCK-SHOP-001',
      adapterName: 'MOCK_YOUZAN_CUSTOMER_DEV_ONLY',
      officialEnabled: false,
      scrmCursor: { sourceMode: 'SCRM_LIST', hasCursor: false, hasMore: false, pageNo: null, itemOffset: null, windowStart: null, windowEnd: null, lastAnchorAt: null, lastSyncedAt: null, totalImportedCount: 0 },
      orderBuyersCursor: { sourceMode: 'ORDER_BUYERS', hasCursor: false, hasMore: false, pageNo: null, itemOffset: null, windowStart: null, windowEnd: null, lastAnchorAt: null, lastSyncedAt: null, totalImportedCount: 0 },
    })
    vi.mocked(listYouzanCustomerSyncRuns).mockResolvedValue({ items: [], total: 0, page: 1, size: 10 })
    vi.mocked(listYouzanCustomerSyncRunItems).mockResolvedValue({ items: [], total: 0, page: 1, size: 20 })
    vi.mocked(pullYouzanCustomers).mockResolvedValue({
      id: 901,
      shopId: 'MOCK-SHOP-001',
      sourceMode: 'SCRM_LIST',
      pullMode: 'BATCH',
      requestedLimit: 10,
      ownerStaffId: 9,
      ownerName: '测试管家',
      adapterName: 'MOCK_YOUZAN_CUSTOMER_DEV_ONLY',
      status: 'SUCCEEDED',
      fetchedCount: 1,
      createdCount: 1,
      skippedExistingCount: 0,
      mobileConflictCount: 0,
      failedCount: 0,
      hasMore: false,
      warnings: [],
      errorMasked: null,
      startedAt: '2026-11-26T09:00:00Z',
      finishedAt: '2026-11-26T09:00:01Z',
      items: [],
    })
    vi.mocked(resetYouzanCustomerSyncCursor).mockResolvedValue({
      shopId: 'MOCK-SHOP-001',
      adapterName: 'MOCK_YOUZAN_CUSTOMER_DEV_ONLY',
      officialEnabled: false,
      scrmCursor: { sourceMode: 'SCRM_LIST', hasCursor: false, hasMore: false, pageNo: null, itemOffset: null, windowStart: null, windowEnd: null, lastAnchorAt: null, lastSyncedAt: null, totalImportedCount: 0 },
      orderBuyersCursor: { sourceMode: 'ORDER_BUYERS', hasCursor: false, hasMore: false, pageNo: null, itemOffset: null, windowStart: null, windowEnd: null, lastAnchorAt: null, lastSyncedAt: null, totalImportedCount: 0 },
    })
    vi.mocked(listCustomerOwnerOptions).mockResolvedValue([{ id: 9, displayName: '测试管家', teamId: 1, teamName: '管家一组' }])
    vi.mocked(listSkus).mockResolvedValue({ items: [sku], total: 1, page: 1, size: 20 })
    vi.mocked(listCustomers).mockResolvedValue({ items: [customer], total: 1, page: 1, size: 20 })
    vi.mocked(getCustomer).mockResolvedValue(customerDetail)
  })

  it('显著展示真实接入预建/Q11待联调，订单金额分转元，缺写权限只读', async () => {
    const wrapper = mountView(['order:read'])
    await flushPromises()

    expect(wrapper.get('[data-testid="youzan-mock-banner"]').text()).toContain('模拟演示')
    expect(wrapper.text()).toContain('真实接入功能已预建')
    expect(wrapper.text()).toContain('待授权联调')
    expect(wrapper.text()).not.toContain('Q11')
    expect(wrapper.text()).toContain('3622.00')
    expect(wrapper.text()).toContain('处理完成')
    expect(wrapper.text()).not.toContain('接口未返回')
    expect(wrapper.find('[data-testid="youzan-sync"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="youzan-reconcile"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="sku-mapping-create"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="customer-link-create"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="issue-retry"]').exists()).toBe(false)
  })

  it('缺 order:read 时不调用任何有赞或客户接口并显示权限错误', async () => {
    const wrapper = mountView([])
    await flushPromises()

    expect(listYouzanOrders).not.toHaveBeenCalled()
    expect(getYouzanReconcileReport).not.toHaveBeenCalled()
    expect(listYouzanSyncIssues).not.toHaveBeenCalled()
    expect(listYouzanSkuCandidates).not.toHaveBeenCalled()
    expect(listYouzanSkuMappings).not.toHaveBeenCalled()
    expect(listYouzanCustomerLinks).not.toHaveBeenCalled()
    expect(batchRetryYouzanSyncIssues).not.toHaveBeenCalled()
    expect(syncYouzanOrders).not.toHaveBeenCalled()
    expect(reconcileYouzanOrders).not.toHaveBeenCalled()
    expect(getOrder).not.toHaveBeenCalled()
    expect(getCustomer).not.toHaveBeenCalled()
    expect(listCustomers).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('当前账号暂无订单查看或有赞接入管理权限')
  })

  it('立即拉取使用 reconcile，异常不包装成成功且不会误调 sync', async () => {
    const warning = vi.spyOn(ElMessage, 'warning')
    const success = vi.spyOn(ElMessage, 'success')
    vi.mocked(reconcileYouzanOrders)
      .mockResolvedValueOnce({ received: 2, processed: 1, duplicates: 0, issues: 1, nextCursor: '2', hasMore: false })
      .mockResolvedValueOnce({ received: 2, processed: 2, duplicates: 0, issues: 0, nextCursor: '4', hasMore: false })
    const wrapper = mountView(['order:read', 'integration:youzan:manage'])
    await flushPromises()

    await wrapper.get('[data-testid="youzan-reconcile"]').trigger('click')
    await flushPromises()
    expect(reconcileYouzanOrders).toHaveBeenNthCalledWith(1, { shopId: 'MOCK-SHOP-001', cursor: undefined, limit: 50, maxOrders: 50 }, expect.any(String))
    expect(syncYouzanOrders).not.toHaveBeenCalled()
    expect(warning).toHaveBeenCalledWith(expect.stringContaining('不能视为全部成功'))

    await wrapper.get('[data-testid="youzan-reconcile"]').trigger('click')
    await flushPromises()
    expect(reconcileYouzanOrders).toHaveBeenCalledTimes(2)
    expect(success).toHaveBeenCalledWith(expect.stringContaining('模拟演示同步完成'))
    expect(listYouzanOrders).toHaveBeenCalledTimes(3)
    expect(getYouzanReconcileReport).toHaveBeenCalledTimes(3)
  })

  it('保留手动 sync 入口并沿用独立端点与异常提示', async () => {
    const warning = vi.spyOn(ElMessage, 'warning')
    const success = vi.spyOn(ElMessage, 'success')
    const wrapper = mountView(['order:read', 'integration:youzan:manage'])
    await flushPromises()

    const button = wrapper.find('[data-testid="youzan-sync"]')
    expect(button.exists()).toBe(true)
    await button.trigger('click')
    await flushPromises()

    expect(syncYouzanOrders).toHaveBeenCalledWith({ shopId: 'MOCK-SHOP-001', cursor: undefined, limit: 50 }, expect.any(String))
    expect(reconcileYouzanOrders).not.toHaveBeenCalled()
    expect(warning).toHaveBeenCalledWith(expect.stringContaining('不能视为全部成功'))
    expect(success).not.toHaveBeenCalled()
  })

  it('原始订单保留完整业务列并展示脱敏字段、归属管家、商家备注、活动归属和真实 CRM 客户链接', async () => {
    const rawOrder: YouzanOrderView = {
      ...order,
      payloadMaskedJson: '{"event_id":"YZ-MOCK-PAID-001","external_customer_id":"YZ-CUSTOMER-1","nickname":"脱敏昵称","mobile":"138****0000","items":[{"external_sku_id":"YZ-SKU-301","quantity":2}],"order_type_code":"CAMPAIGN","campaign_id":7,"campaign_batch_id":3,"paid_at":"2026-10-24T03:00:00Z"}',
      assignedStewardId: 9,
      assignedStewardName: '测试管家',
      assignmentStatus: 'ASSIGNED',
      assignmentSource: 'AUTO_ALIAS',
      memoAccountName: '紫紫模拟',
      memoPerformanceOwner: '陈顾问',
      memoInstitutionName: '杭州示范院',
    }
    vi.mocked(listYouzanOrders).mockResolvedValue({ items: [rawOrder], total: 1, page: 1, size: 20 })
    const wrapper = mountView(['order:read', 'integration:youzan:manage'])
    await flushPromises()

    expect(wrapper.findAll('.el-table__header-wrapper th').map((header) => header.text())).toEqual([
      '有赞订单号',
      '客户昵称 / 手机',
      '商品',
      '实付',
      '归属管家',
      '商家备注摘要',
      '订单类型',
      '活动 / 批次',
      '支付时间',
      'CRM匹配',
      '状态',
      '操作',
    ])
    expect(wrapper.text()).toContain('脱敏昵称')
    expect(wrapper.text()).toContain('138****0000')
    expect(wrapper.text()).toContain('YZ-SKU-301 × 2')
    expect(wrapper.text()).toContain('测试管家')
    expect(wrapper.text()).toContain('账号：紫紫模拟')
    expect(wrapper.text()).toContain('业绩：陈顾问')
    expect(wrapper.text()).toContain('到院：杭州示范院')
    expect(wrapper.text()).toContain('活动 #7')
    expect(wrapper.text()).toContain('批次 #3')
    expect(wrapper.text()).toContain('未知订单类型')
    expect(getCustomer).toHaveBeenCalledWith(42)
    expect(wrapper.text()).toContain('测试客户')
  })

  it.each([
    { mappingStatus: 'CUSTOMER_UNRESOLVED', targetTab: '人工客户链接' },
    { mappingStatus: 'MAPPING_FAILED', targetTab: 'SKU 映射' },
  ])('匹配并同步只进入 $targetTab，不自动保存或调用同步 ($mappingStatus)', async ({ mappingStatus, targetTab }) => {
    const pendingOrder: YouzanOrderView = {
      ...order,
      mapId: null,
      orderId: null,
      status: mappingStatus,
      paidFen: null,
      mappingStatus,
      orderStatus: null,
      verificationMethod: null,
    }
    vi.mocked(listYouzanOrders).mockResolvedValue({ items: [pendingOrder], total: 1, page: 1, size: 20 })
    const wrapper = mountView(['order:read', 'campaign:write'])
    await flushPromises()

    const button = wrapper.findAll('button').find((item) => item.text() === '匹配并同步')
    expect(button).toBeDefined()
    await button?.trigger('click')
    await flushPromises()

    expect(wrapper.get('.el-tabs__item.is-active').text()).toBe(targetTab)
    expect(saveYouzanCustomerLink).not.toHaveBeenCalled()
    expect(saveYouzanSkuMapping).not.toHaveBeenCalled()
    expect(syncYouzanOrders).not.toHaveBeenCalled()
    expect(reconcileYouzanOrders).not.toHaveBeenCalled()
    expect(retryYouzanSyncIssue).not.toHaveBeenCalled()
  })

  it('有内部订单时读取最后一条支付核验，普通管家详情不展示 Raw', async () => {
    vi.mocked(getOrder).mockResolvedValue({
      order: {
        order: internalOrder,
        items: [],
        payments: [{
          id: 500,
          orderId: 71,
          paymentChannel: 'YOUZAN',
          receiverType: 'PLATFORM',
          externalTradeNo: 'YZ-MOCK-TRADE-000',
          amountFen: 362200,
          status: 'CONFIRMED',
          confirmedAt: '2026-10-24T02:00:00Z',
          confirmedBy: 1,
          voucherFileId: null,
          evidenceRef: 'youzan.raw:900',
          verificationMethod: 'MANUAL_REVIEW',
          createdAt: '2026-10-24T02:00:00Z',
          version: 0,
        }, {
          id: 501,
          orderId: 71,
          paymentChannel: 'YOUZAN',
          receiverType: 'PLATFORM',
          externalTradeNo: 'YZ-MOCK-TRADE-001',
          amountFen: 362200,
          status: 'CONFIRMED',
          confirmedAt: '2026-10-24T03:00:00Z',
          confirmedBy: 1,
          voucherFileId: null,
          evidenceRef: 'youzan.raw:901',
          verificationMethod: 'PROVIDER_CALLBACK',
          createdAt: '2026-10-24T03:00:00Z',
          version: 0,
        }],
        history: [],
      },
      entitlements: [],
    })
    const detailOrder = {
      ...order,
      mobile: '13812345678',
      payloadCipher: 'must-not-render-ciphertext',
      payloadMaskedJson: '{"event_id":"YZ-MOCK-PAID-001","mobile":"138****0000"}',
    }
    vi.mocked(listYouzanOrders).mockResolvedValue({ items: [detailOrder], total: 1, page: 1, size: 20 })
    const wrapper = mountView(['order:read'])
    await flushPromises()

    await wrapper.get('[data-testid="youzan-order-detail"]').trigger('click')
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 30))

    expect(getOrder).toHaveBeenCalledWith(71)
    expect(document.body.textContent).toContain('#71')
    expect(document.body.textContent).toContain('供应商支付回调')
    expect(document.body.textContent).toContain('有赞更新时间')
    expect(document.body.textContent).toContain('2026')
    expect(document.body.textContent).not.toContain('原始脱敏详情')
    expect(document.body.textContent).not.toContain('must-not-render-ciphertext')
    expect(document.body.textContent).not.toContain('13812345678')
    expect(document.body.textContent).not.toContain('接口未返回')
  })

  it('失败 Raw 无内部订单时直接打开详情且不加载内部支付', async () => {
    const error = vi.spyOn(ElMessage, 'error')
    const failedOrder: YouzanOrderView = {
      ...order,
      mapId: null,
      orderId: null,
      externalOrderNo: 'YZ-MOCK-FAILED-001',
      status: 'MAPPING_FAILED',
      paidFen: null,
      eventId: 'YZ-EVENT-FAILED-001',
      mappingStatus: 'MAPPING_FAILED',
      orderStatus: null,
      sourceUpdatedAt: '2026-10-24T04:00:00Z',
      externalAmountFen: 129900,
      verificationMethod: null,
      payloadMaskedJson: '{"event_id":"YZ-MOCK-FAILED-001","mobile":"***"}',
      errorReason: '外部 SKU 未映射：YZ-SKU-404',
    }
    vi.mocked(listYouzanOrders).mockResolvedValue({ items: [failedOrder], total: 1, page: 1, size: 20 })
    const wrapper = mountView(['order:read'])
    await flushPromises()

    expect(wrapper.text()).toContain('YZ-MOCK-FAILED-001')
    expect(wrapper.text()).toContain('1299.00')
    expect(wrapper.text()).toContain('SKU 映射失败')

    await wrapper.get('[data-testid="youzan-order-detail"]').trigger('click')
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 30))

    expect(getOrder).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('YZ-EVENT-FAILED-001')
    expect(document.body.textContent).toContain('外部 SKU 未映射：YZ-SKU-404')
    expect(document.body.textContent).toContain('1299.00')
    expect(document.body.textContent).toContain('尚未生成')
    expect(document.body.textContent).toContain('尚未确认')
    expect(document.body.textContent).toContain('2026')
    expect(document.body.textContent).not.toContain('原始脱敏详情')
    expect(document.body.textContent).not.toContain('"event_id":"YZ-MOCK-FAILED-001"')
    expect(document.body.textContent).not.toContain('#null')
    expect(document.body.textContent).not.toContain('接口未返回')
    expect(error).not.toHaveBeenCalledWith(expect.stringContaining('内部支付流水加载失败'))
  })

  it('异常重试失败后沿用同一幂等键并刷新真实状态', async () => {
    vi.mocked(retryYouzanSyncIssue)
      .mockRejectedValueOnce(new ApiError({ status: 0, code: 'NETWORK_ERROR', message: '重试失败测试' }))
      .mockResolvedValueOnce({ id: 901, eventId: 'YZ-MOCK-PAID-001', eventType: 'ORDER_PAID', externalOrderNo: 'YZ-MOCK-PAID-001', processStatus: 'PROCESSED', processAttempts: 2, lastError: null, orderId: 71, receivedAt: '2026-10-24T01:00:00Z', processedAt: '2026-10-24T02:00:00Z', duplicate: true })
    const wrapper = mountView(['order:read', 'campaign:write'])
    await flushPromises()
    await openTab(wrapper, '异常队列')

    await wrapper.get('[data-testid="issue-retry"]').trigger('click')
    await flushPromises()
    expect(listYouzanOrders).toHaveBeenCalledTimes(1)
    await wrapper.get('[data-testid="issue-retry"]').trigger('click')
    await flushPromises()

    expect(retryYouzanSyncIssue).toHaveBeenCalledTimes(2)
    expect(vi.mocked(retryYouzanSyncIssue).mock.calls[0]?.[0]).toBe(801)
    expect(vi.mocked(retryYouzanSyncIssue).mock.calls[1]?.[0]).toBe(801)
    const firstKey = vi.mocked(retryYouzanSyncIssue).mock.calls[0]?.[1]
    const secondKey = vi.mocked(retryYouzanSyncIssue).mock.calls[1]?.[1]
    expect(firstKey).toEqual(expect.stringMatching(/^youzan\.issue\.retry:/))
    expect(secondKey).toBe(firstKey)
    expect(listYouzanOrders).toHaveBeenCalledTimes(2)
  })

  it('SKU 映射使用远程真实 SKU 并保存版本与幂等键', async () => {
    const wrapper = mountView(['order:read', 'campaign:write'])
    await flushPromises()
    await openTab(wrapper, 'SKU 映射')
    await wrapper.get('[data-testid="sku-mapping-create"]').trigger('click')
    await flushPromises()

    await wrapper.get('[data-testid="sku-mapping-external"]').setValue('YZ-SKU-NEW')
    component(wrapper, 'sku-mapping-crm').vm.$emit('change', 301)
    await wrapper.get('[data-testid="sku-mapping-save"]').trigger('click')
    await flushPromises()

    expect(listSkus).toHaveBeenCalledWith({ page: 1, size: 20, keyword: undefined, status: 'ACTIVE' })
    expect(saveYouzanSkuMapping).toHaveBeenCalledWith({ shopId: 'MOCK-SHOP-001', externalSkuId: 'YZ-SKU-NEW', crmSkuId: 301, version: undefined }, expect.any(String))
  })

  it('人工链接必须填写理由，不能只在选择客户后自动匹配', async () => {
    const wrapper = mountView(['order:read', 'campaign:write'])
    await flushPromises()
    await openTab(wrapper, '人工客户链接')
    await wrapper.get('[data-testid="customer-link-create"]').trigger('click')
    await flushPromises()

    await wrapper.get('[data-testid="customer-link-external"]').setValue('YZ-CUSTOMER-NEW')
    component(wrapper, 'customer-link-crm').vm.$emit('change', 42)
    await wrapper.get('[data-testid="customer-link-save"]').trigger('click')
    expect(saveYouzanCustomerLink).not.toHaveBeenCalled()

    await wrapper.get('[data-testid="customer-link-reason"]').setValue('人工核对订单与CRM档案')
    await wrapper.get('[data-testid="customer-link-save"]').trigger('click')
    await flushPromises()
    expect(saveYouzanCustomerLink).toHaveBeenCalledWith(expect.objectContaining({ customerId: 42, reason: '人工核对订单与CRM档案' }), expect.any(String))
  })

  it('切换店铺时清空同步游标，防止跨店游标泄漏', async () => {
    vi.mocked(syncYouzanOrders)
      .mockResolvedValueOnce({ received: 2, processed: 2, duplicates: 0, issues: 0, nextCursor: 'mock-cursor-2', hasMore: true })
      .mockResolvedValueOnce({ received: 1, processed: 1, duplicates: 0, issues: 0, nextCursor: 'real-cursor-2', hasMore: false })
      .mockResolvedValueOnce({ received: 1, processed: 1, duplicates: 0, issues: 0, nextCursor: null, hasMore: false })
    vi.mocked(listYouzanShops).mockResolvedValue([{
      id: 11,
      shopId: '123456',
      shopName: '正式美学店',
      clientId: 'yz-client',
      secretConfigured: true,
      tokenExpiresAt: '2026-12-01T00:00:00Z',
      connectionStatus: 'CONNECTED',
      syncEnabled: true,
      lastError: null,
      lastConnectedAt: '2026-11-20T00:00:00Z',
      webhookPath: '/api/v1/webhooks/youzan/official',
      version: 1,
    }])

    const wrapper = mountView(['order:read', 'integration:youzan:manage'])
    await flushPromises()

    await wrapper.get('[data-testid="youzan-sync"]').trigger('click')
    await flushPromises()
    expect(syncYouzanOrders).toHaveBeenNthCalledWith(1, { shopId: '123456', cursor: undefined, limit: 50 }, expect.any(String))

    await wrapper.get('[data-testid="youzan-shop-id"]').setValue('MOCK-SHOP-001')
    await flushPromises()
    await wrapper.get('[data-testid="youzan-sync"]').trigger('click')
    await flushPromises()
    expect(syncYouzanOrders).toHaveBeenNthCalledWith(2, { shopId: 'MOCK-SHOP-001', cursor: undefined, limit: 50 }, expect.any(String))

    await openTab(wrapper, '店铺接入')
    const shopButton = wrapper.findAll('button').find((item) => item.text() === '正式美学店')
    expect(shopButton).toBeDefined()
    await shopButton?.trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="youzan-sync"]').trigger('click')
    await flushPromises()
    expect(syncYouzanOrders).toHaveBeenNthCalledWith(3, { shopId: '123456', cursor: undefined, limit: 50 }, expect.any(String))
  })

  it('真实店铺激活时展示真实接入横幅与同步提示，不再误报模拟或 Q11 禁用', async () => {
    const success = vi.spyOn(ElMessage, 'success')
    vi.mocked(listYouzanShops).mockResolvedValue([{
      id: 11,
      shopId: '123456',
      shopName: '正式美学店',
      clientId: 'yz-client',
      secretConfigured: true,
      tokenExpiresAt: '2026-12-01T00:00:00Z',
      connectionStatus: 'CONNECTED',
      syncEnabled: true,
      lastError: null,
      lastConnectedAt: '2026-11-20T00:00:00Z',
      webhookPath: '/api/v1/webhooks/youzan/official',
      version: 1,
    }])
    const wrapper = mountView(['order:read', 'integration:youzan:manage'])
    await flushPromises()

    await wrapper.get('[data-testid="youzan-shop-id"]').setValue('123456')
    await flushPromises()

    expect(wrapper.get('[data-testid="youzan-real-banner"]').text()).toContain('正式美学店')
    expect(wrapper.text()).not.toContain('Q11 待授权联调')
    expect(wrapper.text()).not.toContain('当前 Q11 未确认，真实 API 保持禁用')

    await wrapper.get('[data-testid="youzan-reconcile"]').trigger('click')
    await flushPromises()
    expect(success).toHaveBeenCalledWith(expect.stringContaining('同步完成：'))
    expect(success).not.toHaveBeenCalledWith(expect.stringContaining('模拟演示同步完成'))
  })

  it('编辑已有 SKU 映射和人工客户链接时禁用外部自然键输入框', async () => {
    const wrapper = mountView(['order:read', 'campaign:write'])
    await flushPromises()

    await openTab(wrapper, 'SKU 映射')
    await wrapper.get('[data-testid="sku-mapping-edit"]').trigger('click')
    await flushPromises()
    expect((wrapper.get('[data-testid="sku-mapping-external"]').element as HTMLInputElement).disabled).toBe(true)

    await openTab(wrapper, '人工客户链接')
    await wrapper.get('[data-testid="customer-link-edit"]').trigger('click')
    await flushPromises()
    expect((wrapper.get('[data-testid="customer-link-external"]').element as HTMLInputElement).disabled).toBe(true)
  })

  it('连接失败店铺在横幅显示连接失败，查询与映射面板均使用去空白后的 shopId', async () => {
    vi.mocked(listYouzanShops).mockResolvedValue([{
      id: 12,
      shopId: '123456',
      shopName: '失败测试店',
      clientId: 'yz-client',
      secretConfigured: true,
      tokenExpiresAt: null,
      connectionStatus: 'FAILED',
      syncEnabled: false,
      lastError: 'token expired',
      lastConnectedAt: null,
      webhookPath: '/api/v1/webhooks/youzan/official',
      version: 1,
    }])
    const wrapper = mountView(['order:read', 'campaign:write', 'integration:youzan:manage'])
    await flushPromises()

    await wrapper.get('[data-testid="youzan-shop-id"]').setValue('  123456  ')
    await flushPromises()

    const bannerText = wrapper.get('[data-testid="youzan-real-banner"]').text()
    expect(bannerText).toContain('失败测试店')
    expect(bannerText).toContain('连接失败')
    expect(bannerText).not.toContain('连接待验证')

    const queryButton = wrapper.findAll('.shop-card button').find((item) => item.text() === '查询')
    expect(queryButton).toBeDefined()
    await queryButton?.trigger('click')
    await flushPromises()

    expect(listYouzanOrders).toHaveBeenLastCalledWith({ shopId: '123456', page: 1, size: 20 })
    expect(getYouzanReconcileReport).toHaveBeenLastCalledWith('123456')
    expect(listYouzanSkuMappings).toHaveBeenLastCalledWith('123456')
    expect(listYouzanCustomerLinks).toHaveBeenLastCalledWith('123456')

    await openTab(wrapper, 'SKU 映射')
    await wrapper.get('[data-testid="sku-mapping-create"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="sku-mapping-external"]').setValue('YZ-SKU-TRIM')
    component(wrapper, 'sku-mapping-crm').vm.$emit('change', 301)
    await wrapper.get('[data-testid="sku-mapping-save"]').trigger('click')
    await flushPromises()
    expect(saveYouzanSkuMapping).toHaveBeenCalledWith(expect.objectContaining({ shopId: '123456', externalSkuId: 'YZ-SKU-TRIM', crmSkuId: 301 }), expect.any(String))
  })

  it('真实订单无顶层昵称手机号且普通只读无 Raw 时，通过结构化字段展示 yz_open_id、SKU 与支付时间', async () => {
    const realStructuredOrder: YouzanOrderView = {
      ...order,
      shopId: '123456',
      externalShopId: '123456',
      externalOrderNo: 'E202611240001',
      payloadMaskedJson: null,
      externalCustomerId: 'YZ-CUSTOMER-1',
      paidAt: '2026-10-24T03:00:00Z',
      orderTypeCode: 'REGULAR',
      campaignId: 9,
      campaignBatchId: 4,
      items: [{ external_sku_id: '301:9', quantity: 2 }],
    }
    vi.mocked(listYouzanOrders).mockResolvedValue({ items: [realStructuredOrder], total: 1, page: 1, size: 20 })
    const wrapper = mountView(['order:read'])
    await flushPromises()

    expect(wrapper.text()).toContain('YZ-CUSTOMER-1')
    expect(wrapper.text()).toContain('有赞外部客户')
    expect(wrapper.text()).toContain('301:9 × 2')
    expect(wrapper.text()).toContain('活动 #9')
    expect(wrapper.text()).toContain('批次 #4')
    expect(wrapper.text()).toContain('测试客户')
    expect(wrapper.text()).not.toContain('未支付')
  })

  it('仅具备 integration:youzan:manage 时可读取列表并操作映射，匹配并同步自动带出外部标识建议', async () => {
    const unresolvedOrder: YouzanOrderView = {
      ...order,
      mapId: null,
      orderId: null,
      status: 'CUSTOMER_UNRESOLVED',
      mappingStatus: 'CUSTOMER_UNRESOLVED',
      paidFen: null,
      orderStatus: null,
      verificationMethod: null,
      payloadMaskedJson: null,
      externalCustomerId: 'yz-open-prefill-1',
      items: [{ external_sku_id: '555:88', quantity: 1 }],
    }
    vi.mocked(listYouzanOrders).mockResolvedValue({ items: [unresolvedOrder], total: 1, page: 1, size: 20 })
    const wrapper = mountView(['integration:youzan:manage'])
    await flushPromises()

    expect(listYouzanOrders).toHaveBeenCalled()
    expect(wrapper.text()).not.toContain('暂无订单查看或有赞接入管理权限')

    const matchButton = wrapper.findAll('button').find((item) => item.text() === '匹配并同步')
    await matchButton?.trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="customer-link-suggestion"]').text()).toContain('yz-open-prefill-1')

    await wrapper.get('[data-testid="customer-link-create"]').trigger('click')
    await flushPromises()
    expect((wrapper.get('[data-testid="customer-link-external"]').element as HTMLInputElement).value).toBe('yz-open-prefill-1')
    expect(saveYouzanCustomerLink).not.toHaveBeenCalled()
  })

  it('立即拉取订单在 hasMore=true 时保留游标并续拉，直到拉完复位', async () => {
    vi.mocked(reconcileYouzanOrders)
      .mockResolvedValueOnce({ received: 500, processed: 500, duplicates: 0, issues: 0, nextCursor: 'cursor-page-11', hasMore: true })
      .mockResolvedValueOnce({ received: 120, processed: 120, duplicates: 0, issues: 0, nextCursor: 'cursor-page-13', hasMore: false })
      .mockResolvedValueOnce({ received: 10, processed: 10, duplicates: 0, issues: 0, nextCursor: 'cursor-page-1', hasMore: false })
    const wrapper = mountView(['integration:youzan:manage'])
    await flushPromises()

    const reconcileBtn = wrapper.get('[data-testid="youzan-reconcile"]')
    expect(reconcileBtn.text()).toBe('立即拉取订单')

    await reconcileBtn.trigger('click')
    await flushPromises()
    expect(reconcileYouzanOrders).toHaveBeenNthCalledWith(1, { shopId: 'MOCK-SHOP-001', cursor: undefined, limit: 50, maxOrders: 50 }, expect.any(String))
    expect(reconcileBtn.text()).toBe('继续拉取订单')

    await reconcileBtn.trigger('click')
    await flushPromises()
    expect(reconcileYouzanOrders).toHaveBeenNthCalledWith(2, { shopId: 'MOCK-SHOP-001', cursor: 'cursor-page-11', limit: 50, maxOrders: 50 }, expect.any(String))
    expect(reconcileBtn.text()).toBe('立即拉取订单')

    await reconcileBtn.trigger('click')
    await flushPromises()
    expect(reconcileYouzanOrders).toHaveBeenNthCalledWith(3, { shopId: 'MOCK-SHOP-001', cursor: undefined, limit: 50, maxOrders: 50 }, expect.any(String))
  })

  it('已接入真实店铺时自动选中真实店铺，且刷新重挂后保留所选店铺编号避免订单消失', async () => {
    vi.mocked(listYouzanShops).mockResolvedValue([
      {
        id: 11,
        shopId: '40880001',
        shopName: '鲸选官方旗舰店',
        clientId: 'yz-client-1',
        secretConfigured: true,
        tokenExpiresAt: '2026-12-01T00:00:00Z',
        connectionStatus: 'CONNECTED',
        syncEnabled: true,
        lastError: null,
        lastConnectedAt: '2026-11-20T00:00:00Z',
        webhookPath: '/api/v1/webhooks/youzan/official',
        version: 1,
      },
      {
        id: 12,
        shopId: '40880002',
        shopName: '鲸选二号店',
        clientId: 'yz-client-2',
        secretConfigured: true,
        tokenExpiresAt: '2026-12-01T00:00:00Z',
        connectionStatus: 'CONNECTED',
        syncEnabled: true,
        lastError: null,
        lastConnectedAt: '2026-11-20T00:00:00Z',
        webhookPath: '/api/v1/webhooks/youzan/official',
        version: 1,
      },
    ])

    const firstMount = mountView(['order:read', 'integration:youzan:manage'])
    await flushPromises()

    expect(listYouzanOrders).toHaveBeenLastCalledWith({ shopId: '40880001', page: 1, size: 20 })
    expect(firstMount.get('[data-testid="youzan-real-banner"]').text()).toContain('鲸选官方旗舰店')

    await firstMount.get('[data-testid="youzan-quick-shop-40880002"]').trigger('click')
    await flushPromises()
    expect(listYouzanOrders).toHaveBeenLastCalledWith({ shopId: '40880002', page: 1, size: 20 })

    // 模拟浏览器刷新（卸载并重新挂载页面）
    firstMount.unmount()
    vi.mocked(listYouzanOrders).mockClear()

    const refreshed = mountView(['order:read', 'integration:youzan:manage'])
    await flushPromises()

    expect(listYouzanOrders).toHaveBeenCalledWith({ shopId: '40880002', page: 1, size: 20 })
    expect(listYouzanOrders).not.toHaveBeenCalledWith({ shopId: 'MOCK-SHOP-001', page: 1, size: 20 })
    expect(refreshed.get('[data-testid="youzan-real-banner"]').text()).toContain('鲸选二号店')
  })

  it('手动设定拉取条数（10条、20条、500条与自定义）正确传递 limit/maxOrders 并清空旧游标', async () => {
    vi.mocked(reconcileYouzanOrders)
      .mockResolvedValueOnce({ received: 10, processed: 10, duplicates: 0, issues: 0, nextCursor: 'cursor-10-p2', hasMore: true })
      .mockResolvedValueOnce({ received: 20, processed: 20, duplicates: 0, issues: 0, nextCursor: 'cursor-20-p2', hasMore: true })
      .mockResolvedValueOnce({ received: 500, processed: 500, duplicates: 0, issues: 0, nextCursor: null, hasMore: false })
      .mockResolvedValueOnce({ received: 15, processed: 15, duplicates: 0, issues: 0, nextCursor: null, hasMore: false })

    const wrapper = mountView(['order:read', 'integration:youzan:manage'])
    await flushPromises()

    // 设定 10 条并点击立即拉取订单
    await wrapper.get('[data-testid="youzan-pull-preset-10"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="youzan-reconcile"]').trigger('click')
    await flushPromises()
    expect(reconcileYouzanOrders).toHaveBeenNthCalledWith(1, { shopId: 'MOCK-SHOP-001', cursor: undefined, limit: 10, maxOrders: 10 }, expect.any(String))
    expect(wrapper.get('[data-testid="youzan-reconcile"]').text()).toBe('继续拉取订单')

    // 切换到 20 条应清空旧游标，避免分页步长错位
    await wrapper.get('[data-testid="youzan-pull-preset-20"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="youzan-reconcile"]').text()).toBe('立即拉取订单')
    await wrapper.get('[data-testid="youzan-reconcile"]').trigger('click')
    await flushPromises()
    expect(reconcileYouzanOrders).toHaveBeenNthCalledWith(2, { shopId: 'MOCK-SHOP-001', cursor: undefined, limit: 20, maxOrders: 20 }, expect.any(String))

    // 切换到 500 条：reconcile 仅传 maxOrders=500，单页同步上限裁剪为 100
    await wrapper.get('[data-testid="youzan-pull-preset-500"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="youzan-reconcile"]').trigger('click')
    await flushPromises()
    expect(reconcileYouzanOrders).toHaveBeenNthCalledWith(3, { shopId: 'MOCK-SHOP-001', cursor: undefined, maxOrders: 500 }, expect.any(String))

    await wrapper.get('[data-testid="youzan-sync"]').trigger('click')
    await flushPromises()
    expect(syncYouzanOrders).toHaveBeenLastCalledWith({ shopId: 'MOCK-SHOP-001', cursor: undefined, limit: 100 }, expect.any(String))

    // 自定义输入 15 条
    component(wrapper, 'youzan-pull-count').vm.$emit('update:modelValue', 15)
    await flushPromises()
    await wrapper.get('[data-testid="youzan-reconcile"]').trigger('click')
    await flushPromises()
    expect(reconcileYouzanOrders).toHaveBeenNthCalledWith(4, { shopId: 'MOCK-SHOP-001', cursor: undefined, limit: 15, maxOrders: 15 }, expect.any(String))
  })

  it('仅具备 integration:youzan:manage 无 order:read 时打开已映射订单详情不误调内部订单接口且无 403 报错', async () => {
    const error = vi.spyOn(ElMessage, 'error')
    const mappedOrder: YouzanOrderView = {
      ...order,
      verificationMethod: 'PROVIDER_CALLBACK',
      externalCustomerId: 'yz-open-mapped-1',
    }
    vi.mocked(listYouzanOrders).mockResolvedValue({ items: [mappedOrder], total: 1, page: 1, size: 20 })
    const wrapper = mountView(['integration:youzan:manage'])
    await flushPromises()

    await wrapper.get('[data-testid="youzan-order-detail"]').trigger('click')
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 30))

    expect(getOrder).not.toHaveBeenCalled()
    expect(error).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('#71')
    expect(document.body.textContent).toContain('供应商支付回调')
    expect(document.body.textContent).toContain('yz-open-mapped-1')
  })

  it('SKU 映射页展示自动提取的待映射候选表，一键映射自动带出外部 SKU ID 与商品详情卡且不自动重试', async () => {
    const unmappedCandidate: YouzanSkuCandidate = {
      shopId: 'MOCK-SHOP-001',
      externalSkuId: '5505085839:26076889518',
      itemTitle: '鲸选光子嫩肤尊享卡',
      skuSpec: '3次卡 / 全国通用',
      priceFen: 298000,
      outerSkuCode: 'OUTER-GZ-3',
      pendingIssueCount: 12,
      sampleOrderNo: 'E202611260001',
      latestEventAt: '2026-11-26T08:00:00Z',
      mapped: false,
      crmSkuId: null,
    }
    vi.mocked(listYouzanSkuCandidates).mockResolvedValue([unmappedCandidate])
    const wrapper = mountView(['order:read', 'campaign:write'])
    await flushPromises()
    await openTab(wrapper, 'SKU 映射')

    const candidateSection = wrapper.get('[data-testid="sku-candidates-section"]')
    expect(candidateSection.text()).toContain('5505085839:26076889518')
    expect(candidateSection.text()).toContain('鲸选光子嫩肤尊享卡｜3次卡 / 全国通用')
    expect(candidateSection.text()).toContain('¥2980.00')
    expect(candidateSection.text()).toContain('12 条')
    expect(candidateSection.text()).toContain('OUTER-GZ-3')
    expect(candidateSection.text()).toContain('E202611260001')

    await wrapper.get('[data-testid="sku-candidate-map"]').trigger('click')
    await flushPromises()

    expect((wrapper.get('[data-testid="sku-mapping-external"]').element as HTMLInputElement).value).toBe('5505085839:26076889518')
    const metaCard = wrapper.get('[data-testid="sku-mapping-candidate-info"]')
    expect(metaCard.text()).toContain('鲸选光子嫩肤尊享卡｜3次卡 / 全国通用')
    expect(metaCard.text()).toContain('参考单价：¥2980.00')
    expect(metaCard.text()).toContain('商家编码：OUTER-GZ-3')
    expect(metaCard.text()).toContain('待处理异常：12 条')
    expect(metaCard.text()).toContain('示例单号：E202611260001')

    component(wrapper, 'sku-mapping-crm').vm.$emit('change', 301)
    await wrapper.get('[data-testid="sku-mapping-save"]').trigger('click')
    await flushPromises()

    expect(saveYouzanSkuMapping).toHaveBeenCalledWith({
      shopId: 'MOCK-SHOP-001',
      externalSkuId: '5505085839:26076889518',
      crmSkuId: 301,
      version: undefined,
    }, expect.any(String))
    expect(batchRetryYouzanSyncIssues).not.toHaveBeenCalled()
    expect(syncYouzanOrders).not.toHaveBeenCalled()
    expect(reconcileYouzanOrders).not.toHaveBeenCalled()
  })

  it('新增映射弹窗候选下拉选中待映射外部 SKU 后自动填充并可清空', async () => {
    const candidateA: YouzanSkuCandidate = {
      shopId: 'MOCK-SHOP-001',
      externalSkuId: '5505085839:26076889518',
      itemTitle: '水光焕肤疗程',
      skuSpec: '单次体验',
      priceFen: 99000,
      outerSkuCode: null,
      pendingIssueCount: 5,
      sampleOrderNo: 'E202611260002',
      latestEventAt: '2026-11-26T09:00:00Z',
      mapped: false,
      crmSkuId: null,
    }
    vi.mocked(listYouzanSkuCandidates).mockResolvedValue([candidateA])
    const wrapper = mountView(['order:read', 'campaign:write'])
    await flushPromises()
    await openTab(wrapper, 'SKU 映射')

    await wrapper.get('[data-testid="sku-mapping-create"]').trigger('click')
    await flushPromises()

    const externalInput = wrapper.get('[data-testid="sku-mapping-external"]').element as HTMLInputElement
    expect(externalInput.value).toBe('')
    expect(wrapper.find('[data-testid="sku-mapping-candidate-info"]').exists()).toBe(false)

    component(wrapper, 'sku-mapping-candidate-select').vm.$emit('change', '5505085839:26076889518')
    await flushPromises()

    expect(externalInput.value).toBe('5505085839:26076889518')
    expect(wrapper.get('[data-testid="sku-mapping-candidate-info"]').text()).toContain('水光焕肤疗程｜单次体验')
    expect(wrapper.get('[data-testid="sku-mapping-candidate-info"]').text()).toContain('¥990.00')

    component(wrapper, 'sku-mapping-candidate-select').vm.$emit('change', undefined)
    await flushPromises()
    expect(externalInput.value).toBe('')
    expect(wrapper.find('[data-testid="sku-mapping-candidate-info"]').exists()).toBe(false)
  })

  it('异常队列去配映射跳转并自动打开预填弹窗，关闭后切换页签不误弹，已映射后改为展示已配映射', async () => {
    const unmappedIssue: YouzanSyncIssue = {
      ...issue,
      id: 810,
      eventId: 910,
      issueType: 'MAPPING_FAILED',
      status: 'OPEN',
      detailMasked: '外部SKU未映射或数量无效: 5505085839:26076889518',
      lastError: '外部SKU未映射或数量无效: 5505085839:26076889518',
    }
    const mappedIssue: YouzanSyncIssue = {
      ...issue,
      id: 811,
      eventId: 911,
      issueType: 'MAPPING_FAILED',
      status: 'OPEN',
      detailMasked: '外部 SKU 未映射：YZ-SKU-301',
      lastError: '外部 SKU 未映射：YZ-SKU-301',
    }
    vi.mocked(listYouzanSyncIssues).mockResolvedValue({ items: [unmappedIssue, mappedIssue], total: 2, page: 1, size: 20 })
    const wrapper = mountView(['order:read', 'campaign:write'])
    await flushPromises()
    await openTab(wrapper, '异常队列')

    expect(wrapper.findAll('[data-testid="issue-map-sku"]')).toHaveLength(1)
    expect(wrapper.findAll('[data-testid="issue-sku-mapped-hint"]')).toHaveLength(1)
    expect(wrapper.get('[data-testid="issue-sku-mapped-hint"]').text()).toContain('已配映射')

    await wrapper.get('[data-testid="issue-map-sku"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('.el-tabs__item.is-active').text()).toBe('SKU 映射')
    expect((wrapper.get('[data-testid="sku-mapping-external"]').element as HTMLInputElement).value).toBe('5505085839:26076889518')
    expect(saveYouzanSkuMapping).not.toHaveBeenCalled()

    // 关闭弹窗，切走再手动切回 SKU 映射，不应再次自动弹窗
    const cancelBtn = wrapper.findAll('.el-dialog__footer button').find((btn) => btn.text() === '取消')
    expect(cancelBtn).toBeDefined()
    await cancelBtn?.trigger('click')
    await flushPromises()

    await openTab(wrapper, '订单与原始状态')
    await openTab(wrapper, 'SKU 映射')
    expect(wrapper.find('[data-testid="sku-mapping-external"]').exists()).toBe(false)

    // 再次从异常队列点击同一条去配映射，仍能正常重新弹出预填弹窗
    await openTab(wrapper, '异常队列')
    await wrapper.get('[data-testid="issue-map-sku"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('.el-tabs__item.is-active').text()).toBe('SKU 映射')
    expect((wrapper.get('[data-testid="sku-mapping-external"]').element as HTMLInputElement).value).toBe('5505085839:26076889518')
  })

  it('订单页匹配并同步对缺失 external_sku_id 的订单自动跳转并打开预填弹窗，不自动保存或同步', async () => {
    const failedMultiSkuOrder: YouzanOrderView = {
      ...order,
      mapId: null,
      orderId: null,
      externalOrderNo: 'E202611260099',
      status: 'MAPPING_FAILED',
      mappingStatus: 'MAPPING_FAILED',
      paidFen: null,
      orderStatus: null,
      verificationMethod: null,
      payloadMaskedJson: null,
      items: [
        { external_sku_id: 'YZ-SKU-301', quantity: 1 },
        { external_sku_id: '5505085839:26076889518', quantity: 2 },
      ],
      errorReason: '外部SKU未映射或数量无效: 5505085839:26076889518',
    }
    vi.mocked(listYouzanOrders).mockResolvedValue({ items: [failedMultiSkuOrder], total: 1, page: 1, size: 20 })
    const wrapper = mountView(['order:read', 'campaign:write'])
    await flushPromises()

    const matchButton = wrapper.findAll('button').find((item) => item.text() === '匹配并同步')
    expect(matchButton).toBeDefined()
    await matchButton?.trigger('click')
    await flushPromises()

    expect(wrapper.get('.el-tabs__item.is-active').text()).toBe('SKU 映射')
    expect((wrapper.get('[data-testid="sku-mapping-external"]').element as HTMLInputElement).value).toBe('5505085839:26076889518')
    expect(saveYouzanSkuMapping).not.toHaveBeenCalled()
    expect(syncYouzanOrders).not.toHaveBeenCalled()
    expect(reconcileYouzanOrders).not.toHaveBeenCalled()
    expect(retryYouzanSyncIssue).not.toHaveBeenCalled()
    expect(batchRetryYouzanSyncIssues).not.toHaveBeenCalled()
  })

  it('SKU 映射页与异常队列手动批量重试已映射异常携带幂等键、刷新列表并如实汇总，网络失败重试沿用同键', async () => {
    const warning = vi.spyOn(ElMessage, 'warning')
    const success = vi.spyOn(ElMessage, 'success')
    const readyCandidate: YouzanSkuCandidate = {
      shopId: 'MOCK-SHOP-001',
      externalSkuId: 'YZ-SKU-301',
      itemTitle: '测试套餐商品',
      skuSpec: '3次',
      priceFen: 181100,
      outerSkuCode: null,
      pendingIssueCount: 8,
      sampleOrderNo: 'E202611260100',
      latestEventAt: '2026-11-26T10:00:00Z',
      mapped: true,
      crmSkuId: 301,
    }
    vi.mocked(listYouzanSkuCandidates).mockResolvedValue([readyCandidate])
    vi.mocked(batchRetryYouzanSyncIssues)
      .mockResolvedValueOnce({
        shopId: 'MOCK-SHOP-001',
        externalSkuId: null,
        matched: 8,
        attempted: 5,
        processed: 2,
        advanced: 2,
        stillFailed: 1,
        skipped: 0,
        remaining: 3,
      })
      .mockResolvedValueOnce({
        shopId: 'MOCK-SHOP-001',
        externalSkuId: 'YZ-SKU-301',
        matched: 3,
        attempted: 3,
        processed: 3,
        advanced: 0,
        stillFailed: 0,
        skipped: 0,
        remaining: 0,
      })
      .mockRejectedValueOnce(new ApiError({ status: 0, code: 'NETWORK_ERROR', message: '批量重试网络超时' }))
      .mockResolvedValueOnce({
        shopId: 'MOCK-SHOP-001',
        externalSkuId: null,
        matched: 2,
        attempted: 2,
        processed: 2,
        advanced: 0,
        stillFailed: 0,
        skipped: 0,
        remaining: 0,
      })

    const wrapper = mountView(['order:read', 'campaign:write'])
    await flushPromises()
    await openTab(wrapper, 'SKU 映射')

    // 1. SKU 映射页顶部就绪横幅批量重试（含 advanced/stillFailed/remaining -> warning 如实汇总）
    expect(wrapper.get('[data-testid="sku-ready-retry-banner"]').text()).toContain('已有 1 个已映射外部 SKU 存在待补偿重试异常')
    await wrapper.get('[data-testid="sku-batch-retry-all"]').trigger('click')
    await flushPromises()

    expect(batchRetryYouzanSyncIssues).toHaveBeenNthCalledWith(
      1,
      { shopId: 'MOCK-SHOP-001', limit: 50 },
      expect.stringMatching(/^youzan\.issue\.batch-retry:/),
    )
    expect(warning).toHaveBeenCalledWith(expect.stringContaining('已尝试 5 条：处理完成 2 条'))
    expect(warning).toHaveBeenCalledWith(expect.stringContaining('SKU 已通过待继续处理（客户匹配/支付核验）2 条'))
    expect(warning).toHaveBeenCalledWith(expect.stringContaining('仍失败 1 条'))
    expect(warning).toHaveBeenCalledWith(expect.stringContaining('剩余 3 条可再次点击继续重试'))
    expect(listYouzanOrders).toHaveBeenCalledTimes(2)
    expect(listYouzanSyncIssues).toHaveBeenCalledTimes(2)
    expect(listYouzanSkuCandidates).toHaveBeenCalledTimes(2)
    expect(getYouzanReconcileReport).toHaveBeenCalledTimes(2)

    // 2. SKU 映射行级重试异常(N)（全部 processed=3, remaining=0 -> success）
    expect(wrapper.get('[data-testid="sku-mapping-retry"]').text()).toContain('重试异常(8)')
    await wrapper.get('[data-testid="sku-mapping-retry"]').trigger('click')
    await flushPromises()

    expect(batchRetryYouzanSyncIssues).toHaveBeenNthCalledWith(
      2,
      { shopId: 'MOCK-SHOP-001', externalSkuId: 'YZ-SKU-301', limit: 50 },
      expect.stringMatching(/^youzan\.issue\.batch-retry:/),
    )
    expect(success).toHaveBeenCalledWith('已尝试 3 条：处理完成 3 条')

    // 3. 异常队列工具栏批量重试：首次网络失败后再次点击沿用同一幂等键
    await openTab(wrapper, '异常队列')
    const toolbarBtn = wrapper.get('[data-testid="issues-batch-retry"]')
    expect(toolbarBtn.text()).toContain('批量重试已映射异常（1 个 SKU）')

    await toolbarBtn.trigger('click')
    await flushPromises()
    await toolbarBtn.trigger('click')
    await flushPromises()

    const failedAttemptKey = vi.mocked(batchRetryYouzanSyncIssues).mock.calls[2]?.[1]
    const retriedAttemptKey = vi.mocked(batchRetryYouzanSyncIssues).mock.calls[3]?.[1]
    expect(failedAttemptKey).toEqual(expect.stringMatching(/^youzan\.issue\.batch-retry:/))
    expect(retriedAttemptKey).toBe(failedAttemptKey)
    expect(listYouzanOrders).toHaveBeenCalledTimes(4)
  })

  it('仅具备 order:read 的只读用户不可见一键映射、去配映射与批量重试按钮', async () => {
    const unmappedCandidate: YouzanSkuCandidate = {
      shopId: 'MOCK-SHOP-001',
      externalSkuId: '5505085839:26076889518',
      itemTitle: '只读测试商品',
      skuSpec: '标准版',
      priceFen: 150000,
      outerSkuCode: null,
      pendingIssueCount: 4,
      sampleOrderNo: 'E202611260200',
      latestEventAt: '2026-11-26T10:00:00Z',
      mapped: false,
      crmSkuId: null,
    }
    const readyCandidate: YouzanSkuCandidate = {
      ...unmappedCandidate,
      externalSkuId: 'YZ-SKU-301',
      mapped: true,
      crmSkuId: 301,
    }
    const unmappedIssue: YouzanSyncIssue = {
      ...issue,
      detailMasked: '外部SKU未映射或数量无效: 5505085839:26076889518',
      lastError: '外部SKU未映射或数量无效: 5505085839:26076889518',
    }
    vi.mocked(listYouzanSkuCandidates).mockResolvedValue([unmappedCandidate, readyCandidate])
    vi.mocked(listYouzanSyncIssues).mockResolvedValue({ items: [unmappedIssue], total: 1, page: 1, size: 20 })

    const wrapper = mountView(['order:read'])
    await flushPromises()

    await openTab(wrapper, 'SKU 映射')
    expect(wrapper.find('[data-testid="sku-candidates-section"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="sku-candidates-section"]').text()).toContain('只读权限')
    expect(wrapper.get('[data-testid="sku-candidates-section"]').text()).not.toContain('点击「一键映射」')
    expect(wrapper.find('[data-testid="sku-candidate-map"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="sku-ready-retry-banner"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="sku-batch-retry-all"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="sku-mapping-retry"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="sku-mapping-create"]').exists()).toBe(false)

    await openTab(wrapper, '异常队列')
    expect(wrapper.find('[data-testid="issues-batch-retry"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="issue-map-sku"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="issue-retry"]').exists()).toBe(false)
    expect(wrapper.findAll('.el-tabs__item').some((item) => item.text() === '客户拉取与建档')).toBe(false)
  })

  it('已停用（INACTIVE）SKU 映射通过一键映射与弹窗候选下拉均带出原 version 重新启用避免 409', async () => {
    const inactiveMapping = {
      id: 309,
      shopId: 'MOCK-SHOP-001',
      externalSkuId: '5505085839:26076889518',
      crmSkuId: 301,
      status: 'INACTIVE',
      createdAt: '2026-10-24T01:00:00Z',
      version: 3,
    }
    const inactiveCandidate: YouzanSkuCandidate = {
      shopId: 'MOCK-SHOP-001',
      externalSkuId: '5505085839:26076889518',
      itemTitle: '已停用复用商品',
      skuSpec: '标准版',
      priceFen: 199000,
      outerSkuCode: null,
      pendingIssueCount: 3,
      sampleOrderNo: 'E202611260309',
      latestEventAt: '2026-11-26T10:00:00Z',
      mapped: false,
      crmSkuId: null,
    }
    vi.mocked(listYouzanSkuMappings).mockResolvedValue([inactiveMapping])
    vi.mocked(listYouzanSkuCandidates).mockResolvedValue([inactiveCandidate])

    const wrapper = mountView(['order:read', 'campaign:write'])
    await flushPromises()
    await openTab(wrapper, 'SKU 映射')

    // 1. 通过候选表「一键映射」进入编辑并携带 version=3
    await wrapper.get('[data-testid="sku-candidate-map"]').trigger('click')
    await flushPromises()
    expect((wrapper.get('[data-testid="sku-mapping-external"]').element as HTMLInputElement).disabled).toBe(true)
    expect(wrapper.text()).toContain('当前数据版本：3')
    expect(wrapper.text()).toContain('保存后将重新启用该映射')

    await wrapper.get('[data-testid="sku-mapping-save"]').trigger('click')
    await flushPromises()
    expect(saveYouzanSkuMapping).toHaveBeenNthCalledWith(1, {
      shopId: 'MOCK-SHOP-001',
      externalSkuId: '5505085839:26076889518',
      crmSkuId: 301,
      version: 3,
    }, expect.any(String))

    // 2. 通过「新增映射」弹窗候选下拉选中该已停用 SKU，也应自动带出 version=3
    await wrapper.get('[data-testid="sku-mapping-create"]').trigger('click')
    await flushPromises()
    component(wrapper, 'sku-mapping-candidate-select').vm.$emit('change', '5505085839:26076889518')
    await flushPromises()
    expect((wrapper.get('[data-testid="sku-mapping-external"]').element as HTMLInputElement).disabled).toBe(true)

    await wrapper.get('[data-testid="sku-mapping-save"]').trigger('click')
    await flushPromises()
    expect(saveYouzanSkuMapping).toHaveBeenNthCalledWith(2, {
      shopId: 'MOCK-SHOP-001',
      externalSkuId: '5505085839:26076889518',
      crmSkuId: 301,
      version: 3,
    }, expect.any(String))
  })

  it('异常队列去配映射在目标店铺未检出候选时提示核对店铺，候选加载失败显示告警并可重试', async () => {
    const fallbackIssue: YouzanSyncIssue = {
      ...issue,
      id: 820,
      eventId: 920,
      issueType: 'MAPPING_FAILED',
      status: 'OPEN',
      detailMasked: 'SKU 映射失败，请查看错误原因',
      lastError: '外部SKU未映射或数量无效: 99001122:334455。',
    }
    vi.mocked(listYouzanSyncIssues).mockResolvedValue({ items: [fallbackIssue], total: 1, page: 1, size: 20 })
    vi.mocked(listYouzanSkuCandidates)
      .mockRejectedValueOnce(new ApiError({ status: 500, code: 'INTERNAL_ERROR', message: '候选服务暂不可用' }))
      .mockResolvedValueOnce([])

    const wrapper = mountView(['order:read', 'campaign:write'])
    await flushPromises()
    await openTab(wrapper, 'SKU 映射')

    expect(wrapper.get('[data-testid="sku-candidates-error"]').text()).toContain('候选服务暂不可用')
    await wrapper.get('[data-testid="sku-candidates-retry"]').trigger('click')
    await flushPromises()
    expect(listYouzanSkuCandidates).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="sku-candidates-error"]').exists()).toBe(false)

    // detailMasked 无编码时回退解析 lastError，并在弹窗展示目标店铺与跨店未检出提醒
    await openTab(wrapper, '异常队列')
    await wrapper.get('[data-testid="issue-map-sku"]').trigger('click')
    await flushPromises()

    expect((wrapper.get('[data-testid="sku-mapping-external"]').element as HTMLInputElement).value).toBe('99001122:334455')
    expect(wrapper.get('[data-testid="sku-mapping-target-shop"]').text()).toContain('MOCK-SHOP-001')
    expect(wrapper.get('[data-testid="sku-mapping-target-shop"]').text()).toContain('模拟演示店铺')
    expect(wrapper.get('[data-testid="sku-mapping-shop-mismatch-warn"]').text()).toContain('未检出该外部 SKU')
  })

  it('支持通过 URL ?tab=customer-pull 进入客户拉取与建档页签，并响应 imported 与 link-external 跳转人工链接', async () => {
    window.history.replaceState({}, '', '/integrations/youzan?tab=customer-pull')
    vi.mocked(pullYouzanCustomers).mockResolvedValue({
      id: 902,
      shopId: 'MOCK-SHOP-001',
      sourceMode: 'SCRM_LIST',
      pullMode: 'BATCH',
      requestedLimit: 20,
      ownerStaffId: 9,
      ownerName: '测试管家',
      adapterName: 'MOCK_YOUZAN_CUSTOMER_DEV_ONLY',
      status: 'PARTIAL',
      fetchedCount: 2,
      createdCount: 1,
      skippedExistingCount: 0,
      mobileConflictCount: 1,
      failedCount: 0,
      hasMore: false,
      warnings: [],
      errorMasked: null,
      startedAt: '2026-11-26T09:00:00Z',
      finishedAt: '2026-11-26T09:00:01Z',
      items: [
        {
          id: 1,
          runId: 902,
          shopId: 'MOCK-SHOP-001',
          externalCustomerId: 'yz-open-conflict-88',
          outcome: 'MOBILE_CONFLICT',
          displayNameMasked: '周*士',
          mobileMasked: '138****8888',
          crmCustomerId: null,
          crmCustomerNo: null,
          conflictCustomerIds: [42],
          conflictCustomerNos: ['C0000000042'],
          detailMasked: '手机号命中已有客户',
          createdAt: '2026-11-26T09:00:01Z',
        },
      ],
    })
    vi.mocked(listYouzanCustomerSyncRunItems).mockResolvedValue({
      items: [
        {
          id: 1,
          runId: 902,
          shopId: 'MOCK-SHOP-001',
          externalCustomerId: 'yz-open-conflict-88',
          outcome: 'MOBILE_CONFLICT',
          displayNameMasked: '周*士',
          mobileMasked: '138****8888',
          crmCustomerId: null,
          crmCustomerNo: null,
          conflictCustomerIds: [42],
          conflictCustomerNos: ['C0000000042'],
          detailMasked: '手机号命中已有客户',
          createdAt: '2026-11-26T09:00:01Z',
        },
      ],
      total: 1,
      page: 1,
      size: 20,
    })

    const wrapper = mountView(['order:read', 'integration:youzan:manage', 'customer:write'])
    await flushPromises()

    expect(wrapper.get('.el-tabs__item.is-active').text()).toBe('客户拉取与建档')
    expect(wrapper.find('[data-testid="yz-customer-pull-panel"]').exists()).toBe(true)

    const initialLinkCalls = vi.mocked(listYouzanCustomerLinks).mock.calls.length
    await wrapper.get('[data-testid="yz-customer-pull-batch"]').trigger('click')
    await flushPromises()
    expect(vi.mocked(listYouzanCustomerLinks).mock.calls.length).toBeGreaterThan(initialLinkCalls)

    await wrapper.get('[data-testid="yz-item-link-external"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('.el-tabs__item.is-active').text()).toBe('人工客户链接')
    expect(wrapper.get('[data-testid="customer-link-suggestion"]').text()).toContain('yz-open-conflict-88')
  })

  it('订单列表商品列展示商品标题、规格、外部 SKU 与多商品汇总，同时兼容仅含外部 SKU 的旧行', async () => {
    const richSingleOrder: YouzanOrderView = {
      ...order,
      eventId: 'YZ-EVENT-GOODS-1',
      externalOrderNo: 'E202611260501',
      payloadMaskedJson: null,
      items: [
        {
          external_sku_id: '5505085839:26076889518',
          quantity: 2,
          itemTitle: '鲸选光子嫩肤尊享卡',
          skuSpec: '3次卡 / 全国通用',
          priceFen: 150000,
          paymentFen: 298000,
          outerSkuCode: 'OUTER-GZ-3',
          crmSkuId: 301,
          crmSkuName: '测试套餐',
        },
      ],
    }
    const multiItemOrder: YouzanOrderView = {
      ...order,
      eventId: 'YZ-EVENT-GOODS-2',
      externalOrderNo: 'E202611260502',
      payloadMaskedJson: null,
      items: [
        {
          external_sku_id: '5505085839:777001',
          quantity: 1,
          itemTitle: null,
          skuSpec: '标准版',
          crmSkuId: 301,
          crmSkuName: '热玛吉抗衰套卡',
        },
        {
          external_sku_id: '5505085839:777002',
          quantity: 2,
          itemTitle: '胶原修复面膜',
          skuSpec: '5片装',
          crmSkuId: null,
          crmSkuName: null,
        },
      ],
    }
    const legacyOnlySkuOrder: YouzanOrderView = {
      ...order,
      eventId: 'YZ-EVENT-GOODS-3',
      externalOrderNo: 'E202611260503',
      payloadMaskedJson: null,
      items: [{ external_sku_id: 'LEGACY-SKU-9', quantity: 3 }],
    }
    vi.mocked(listYouzanOrders).mockResolvedValue({
      items: [richSingleOrder, multiItemOrder, legacyOnlySkuOrder],
      total: 3,
      page: 1,
      size: 20,
    })

    const wrapper = mountView(['order:read'])
    await flushPromises()

    const goodsCells = wrapper.findAll('[data-testid="youzan-order-goods"]')
    expect(goodsCells).toHaveLength(3)

    expect(goodsCells[0]?.text()).toContain('鲸选光子嫩肤尊享卡 × 2')
    expect(goodsCells[0]?.find('.proto-subcell').text()).toContain('3次卡 / 全国通用')
    expect(goodsCells[0]?.find('.proto-subcell').text()).toContain('SKU 5505085839:26076889518')

    expect(goodsCells[1]?.text()).toContain('热玛吉抗衰套卡 × 1')
    expect(goodsCells[1]?.text()).toContain('共 2 项')
    expect(goodsCells[1]?.find('.proto-subcell').text()).toContain('标准版')
    expect(goodsCells[1]?.find('.proto-subcell').text()).toContain('合计 3 件')
    expect(goodsCells[1]?.find('.proto-subcell').text()).toContain('另含 胶原修复面膜 × 2')

    expect(goodsCells[2]?.text()).toContain('LEGACY-SKU-9 × 3')
    expect(goodsCells[2]?.find('.proto-subcell').exists()).toBe(false)
  })

  it('管理员支持按有赞订单号单笔拉取、自动打开详情展示备注与分配管家', async () => {
    const success = vi.spyOn(ElMessage, 'success')
    const pulledOrder: YouzanOrderView = {
      ...order,
      externalOrderNo: 'E202611119999',
      providerOrderStatus: 'WAIT_BUYER_CONFIRM_GOODS',
      providerStatusText: '已发货',
      payWayDesc: '微信支付',
      memoAccountName: '紫紫模拟',
      memoPerformanceOwner: '陈顾问',
      memoInstitutionName: '杭州示范院',
      tradeMemoMasked: '业绩：陈顾问，账号：紫紫模拟，到院：杭州示范院',
      assignedStewardId: 9,
      assignedStewardName: '测试管家',
      assignmentStatus: 'ASSIGNED',
      assignmentSource: 'AUTO_ALIAS',
    }
    vi.mocked(pullYouzanOrderByTid).mockResolvedValue(pulledOrder)

    const wrapper = mountView(['order:read', 'integration:youzan:manage'])
    await flushPromises()

    await wrapper.get('[data-testid="youzan-pull-tid-input"]').setValue('E202611119999')
    await wrapper.get('[data-testid="youzan-pull-tid-btn"]').trigger('click')
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 30))

    expect(pullYouzanOrderByTid).toHaveBeenCalledWith(
      { shopId: 'MOCK-SHOP-001', tid: 'E202611119999' },
      expect.stringMatching(/^youzan\.pull-tid:/),
    )
    expect(success).toHaveBeenCalledWith(expect.stringContaining('已自动分配给管家：测试管家'))
    expect(document.body.textContent).toContain('E202611119999')
    expect(document.body.textContent).toContain('杭州示范院')
  })

  it('管理员可在管家账号映射页签新增别名并自动回填历史待分配订单', async () => {
    const success = vi.spyOn(ElMessage, 'success')
    const wrapper = mountView(['order:read', 'integration:youzan:manage'])
    await flushPromises()

    await openTab(wrapper, '管家账号映射')
    await wrapper.get('[data-testid="steward-alias-create"]').trigger('click')
    await flushPromises()

    await wrapper.get('[data-testid="steward-alias-name"]').setValue('紫紫模拟')
    component(wrapper, 'steward-alias-steward').vm.$emit('update:modelValue', 9)
    await flushPromises()
    await wrapper.get('[data-testid="steward-alias-save"]').trigger('click')
    await flushPromises()

    expect(saveYouzanStewardAlias).toHaveBeenCalledWith(
      expect.objectContaining({ aliasName: '紫紫模拟', stewardUserId: 9, status: 'ACTIVE', backfillPending: true }),
      expect.stringMatching(/^youzan\.steward\.alias:/),
    )
    expect(success).toHaveBeenCalledWith(expect.stringContaining('已自动回填 1 笔历史待分配订单'))
  })
})
