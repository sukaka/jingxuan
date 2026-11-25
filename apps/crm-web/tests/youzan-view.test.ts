import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessage } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuthUser } from '../src/api/auth'
import type { CustomerDetail, CustomerListItem } from '../src/api/customer'
import { getCustomer, listCustomers } from '../src/api/customer'
import type { Sku } from '../src/api/catalog'
import { listSkus } from '../src/api/catalog'
import type { YouzanCustomerLink, YouzanOrderView, YouzanSyncIssue } from '../src/api/youzan'
import {
  getYouzanReconcileReport,
  listYouzanCustomerLinks,
  listYouzanOrders,
  listYouzanShops,
  listYouzanSkuMappings,
  listYouzanSyncIssues,
  reconcileYouzanOrders,
  retryYouzanSyncIssue,
  saveYouzanCustomerLink,
  saveYouzanSkuMapping,
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
  listYouzanShops: vi.fn(),
  syncYouzanOrders: vi.fn(),
  reconcileYouzanOrders: vi.fn(),
  getYouzanReconcileReport: vi.fn(),
  listYouzanSyncIssues: vi.fn(),
  retryYouzanSyncIssue: vi.fn(),
  listYouzanSkuMappings: vi.fn(),
  saveYouzanSkuMapping: vi.fn(),
  listYouzanCustomerLinks: vi.fn(),
  saveYouzanCustomerLink: vi.fn(),
}))
vi.mock('../src/api/catalog', () => ({ listSkus: vi.fn(), listInstitutions: vi.fn().mockResolvedValue({ items: [], total: 0, page: 1, size: 100 }), listProjects: vi.fn().mockResolvedValue({ items: [], total: 0, page: 1, size: 100 }) }))
vi.mock('../src/api/customer', () => ({ listCustomers: vi.fn(), getCustomer: vi.fn() }))

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
    vi.mocked(listYouzanOrders).mockResolvedValue({ items: [order], total: 1, page: 1, size: 20 })
    vi.mocked(listYouzanShops).mockResolvedValue([])
    vi.mocked(getYouzanReconcileReport).mockResolvedValue({ shopId: 'MOCK-SHOP-001', totalEvents: 2, internalOrders: 1, openIssues: 1, lastSyncAt: '2026-10-24T02:00:00Z', todayEvents: 2, campaignOrders: 1 })
    vi.mocked(listYouzanSyncIssues).mockResolvedValue({ items: [issue], total: 1, page: 1, size: 20 })
    vi.mocked(listYouzanSkuMappings).mockResolvedValue([skuMapping])
    vi.mocked(listYouzanCustomerLinks).mockResolvedValue([customerLink])
    vi.mocked(syncYouzanOrders).mockResolvedValue({ received: 2, processed: 1, duplicates: 0, issues: 1, nextCursor: '2', hasMore: false })
    vi.mocked(reconcileYouzanOrders).mockResolvedValue({ received: 2, processed: 2, duplicates: 0, issues: 0, nextCursor: '2', hasMore: false })
    vi.mocked(retryYouzanSyncIssue).mockResolvedValue({ id: 901, eventId: 'YZ-MOCK-PAID-001', eventType: 'ORDER_PAID', externalOrderNo: 'YZ-MOCK-PAID-001', processStatus: 'PROCESSED', processAttempts: 2, lastError: null, orderId: 71, receivedAt: '2026-10-24T01:00:00Z', processedAt: '2026-10-24T02:00:00Z', duplicate: true })
    vi.mocked(saveYouzanSkuMapping).mockResolvedValue(skuMapping)
    vi.mocked(saveYouzanCustomerLink).mockResolvedValue(customerLink)
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
    expect(listYouzanSkuMappings).not.toHaveBeenCalled()
    expect(listYouzanCustomerLinks).not.toHaveBeenCalled()
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
    expect(reconcileYouzanOrders).toHaveBeenNthCalledWith(1, { shopId: 'MOCK-SHOP-001', cursor: undefined, limit: 50 }, expect.any(String))
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

  it('原始订单保留 10 列并展示脱敏字段、活动归属和真实 CRM 客户链接', async () => {
    const rawOrder: YouzanOrderView = {
      ...order,
      payloadMaskedJson: '{"event_id":"YZ-MOCK-PAID-001","external_customer_id":"YZ-CUSTOMER-1","nickname":"脱敏昵称","mobile":"138****0000","items":[{"external_sku_id":"YZ-SKU-301","quantity":2}],"order_type_code":"CAMPAIGN","campaign_id":7,"campaign_batch_id":3,"paid_at":"2026-10-24T03:00:00Z"}',
    }
    vi.mocked(listYouzanOrders).mockResolvedValue({ items: [rawOrder], total: 1, page: 1, size: 20 })
    const wrapper = mountView(['order:read', 'integration:youzan:manage'])
    await flushPromises()

    expect(wrapper.findAll('.el-table__header-wrapper th').map((header) => header.text())).toEqual([
      '有赞订单号',
      '客户昵称 / 手机',
      '商品',
      '实付',
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
    expect(syncYouzanOrders).toHaveBeenNthCalledWith(1, { shopId: 'MOCK-SHOP-001', cursor: undefined, limit: 50 }, expect.any(String))

    await wrapper.get('[data-testid="youzan-shop-id"]').setValue('123456')
    await flushPromises()
    await wrapper.get('[data-testid="youzan-sync"]').trigger('click')
    await flushPromises()
    expect(syncYouzanOrders).toHaveBeenNthCalledWith(2, { shopId: '123456', cursor: undefined, limit: 50 }, expect.any(String))

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
    expect(reconcileYouzanOrders).toHaveBeenNthCalledWith(1, { shopId: 'MOCK-SHOP-001', cursor: undefined, limit: 50 }, expect.any(String))
    expect(reconcileBtn.text()).toBe('继续拉取订单')

    await reconcileBtn.trigger('click')
    await flushPromises()
    expect(reconcileYouzanOrders).toHaveBeenNthCalledWith(2, { shopId: 'MOCK-SHOP-001', cursor: 'cursor-page-11', limit: 50 }, expect.any(String))
    expect(reconcileBtn.text()).toBe('立即拉取订单')

    await reconcileBtn.trigger('click')
    await flushPromises()
    expect(reconcileYouzanOrders).toHaveBeenNthCalledWith(3, { shopId: 'MOCK-SHOP-001', cursor: undefined, limit: 50 }, expect.any(String))
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
})
