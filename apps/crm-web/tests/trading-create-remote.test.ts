import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuthUser } from '../src/api/auth'
import type { Sku } from '../src/api/catalog'
import { listSkus } from '../src/api/catalog'
import type { CustomerListItem } from '../src/api/customer'
import { listCustomers } from '../src/api/customer'
import type { CrmOrder, OrderFulfillmentView } from '../src/api/trading'
import { createOrder, getOrder, listOrders } from '../src/api/trading'
import { useAuthStore } from '../src/stores/auth'
import { ApiError } from '../src/types/api'
import OrdersView from '../src/views/OrdersView.vue'

vi.mock('../src/api/trading', () => ({
  listOrders: vi.fn(),
  createOrder: vi.fn(),
  getOrder: vi.fn(),
  submitOrder: vi.fn(),
  cancelOrder: vi.fn(),
  confirmPayment: vi.fn(),
  listEntitlements: vi.fn(),
  getEntitlement: vi.fn(),
}))

vi.mock('../src/api/customer', () => ({
  listCustomers: vi.fn(),
}))

vi.mock('../src/api/catalog', () => ({
  listSkus: vi.fn(),
}))

function makeCustomer(id: number): CustomerListItem {
  return {
    id,
    customerNo: `C${String(id).padStart(10, '0')}`,
    displayName: `测试客户 ${id}`,
    nickname: `远程客户 ${id}`,
    mobileMasked: null,
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
}

function makeSku(id: number, salePriceFen = 19900): Sku {
  return {
    id,
    skuCode: `SKU-REMOTE-${id}`,
    packageVersionId: 201,
    packageId: 101,
    packageName: `测试套餐 ${id}`,
    versionNo: 1,
    selectionMode: 'USE_N',
    specName: `远程规格 ${id}`,
    salePriceFen,
    status: 'ACTIVE',
    onSaleAt: '2026-10-24T01:00:00Z',
    offSaleAt: null,
    createdAt: '2026-10-24T01:00:00Z',
    version: 0,
  }
}

function makeOrder(): CrmOrder {
  return {
    id: 71,
    orderNo: 'J000000000071',
    customerId: 42,
    customerName: '测试客户 42',
    soldByUserId: 9,
    ownerAtSaleId: 9,
    externalOrderNo: null,
    sourceSystem: 'CRM',
    orderTypeCode: 'REGULAR',
    campaignId: null,
    campaignBatchId: null,
    status: 'DRAFT',
    receiverType: 'PLATFORM',
    listAmountFen: 19900,
    discountAmountFen: 0,
    payableFen: 19900,
    paidFen: 0,
    refundedFen: 0,
    orderedAt: '2026-10-24T01:00:00Z',
    paidAt: null,
    createdAt: '2026-10-24T01:00:00Z',
    version: 0,
  }
}

function page<T>(items: T[], total = items.length) {
  return { items, total, page: 1, size: 20 }
}

const savedView: OrderFulfillmentView = {
  order: {
    order: makeOrder(),
    items: [{
      id: 711,
      orderId: 71,
      skuId: 301,
      packageVersionId: 201,
      skuCodeSnapshot: 'SKU-REMOTE-301',
      skuNameSnapshot: '测试套餐 301｜远程规格 301',
      purchaseQuantity: 1,
      listAmountFen: 19900,
      paidAllocatedFen: 0,
      entitlementSnapshot: JSON.stringify({ allocationStatus: 'CONFIGURED', institutionIds: [91] }),
      version: 0,
    }],
    payments: [],
    history: [],
  },
  entitlements: [],
}

const user: AuthUser = {
  userId: 9,
  username: 'trading-remote-test',
  displayName: '远程搜索测试用户',
  userType: 'EMPLOYEE',
  roles: ['MANAGER'],
  permissions: ['order:read', 'order:write'],
}

const wrappers: VueWrapper[] = []
afterEach(() => {
  while (wrappers.length) wrappers.pop()?.unmount()
  document.body.innerHTML = ''
})

function mountView() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = user
  auth.initialized = true
  const wrapper = mount(OrdersView, {
    global: { plugins: [ElementPlus, pinia] },
    attachTo: document.body,
  })
  wrappers.push(wrapper)
  return wrapper
}

async function openCreate() {
  const wrapper = mountView()
  await flushPromises()
  await wrapper.get('[data-testid="order-create"]').trigger('click')
  await flushPromises()
  await new Promise((resolve) => setTimeout(resolve, 40))
  return wrapper
}

function select(wrapper: VueWrapper, testId: string): VueWrapper {
  return wrapper.getComponent(`[data-testid="${testId}"]`) as VueWrapper
}

async function choose(wrapper: VueWrapper, testId: string, id: number) {
  const component = select(wrapper, testId)
  component.vm.$emit('update:modelValue', id)
  component.vm.$emit('change', id)
  await flushPromises()
}

interface RemoteSelectProps {
  remoteMethod: (query: string) => void
  remote: boolean
  filterable: boolean
}

function remoteSelectProps(wrapper: VueWrapper, testId: string): RemoteSelectProps {
  return select(wrapper, testId).vm.$props as RemoteSelectProps
}

async function remoteSearch(wrapper: VueWrapper, testId: string, keyword: string) {
  const method = remoteSelectProps(wrapper, testId).remoteMethod
  expect(method).toBeTypeOf('function')
  method(keyword)
  await flushPromises()
}

describe('OrdersView 新建订单远程选项', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(listOrders).mockResolvedValue(page([makeOrder()]))
    vi.mocked(getOrder).mockResolvedValue(savedView)
    vi.mocked(listCustomers).mockResolvedValue(page([makeCustomer(42)]))
    vi.mocked(listSkus).mockResolvedValue(page([makeSku(301)]))
    vi.mocked(createOrder).mockResolvedValue(savedView)
  })

  it('remote/filterable 通过服务端小分页和关键词搜索，结果过多时提示缩小范围', async () => {
    vi.mocked(listCustomers).mockResolvedValue(page([makeCustomer(42)], 41))
    vi.mocked(listSkus).mockResolvedValue(page([makeSku(301)], 41))
    const wrapper = await openCreate()

    expect(listCustomers).toHaveBeenCalledWith({ page: 1, size: 20, keyword: undefined })
    expect(listSkus).toHaveBeenCalledWith({ page: 1, size: 20, keyword: undefined, status: 'ACTIVE' })
    expect(remoteSelectProps(wrapper, 'order-customer-select').remote).toBe(true)
    expect(remoteSelectProps(wrapper, 'order-customer-select').filterable).toBe(true)
    expect(remoteSelectProps(wrapper, 'order-sku-select').remote).toBe(true)
    expect(wrapper.text()).toContain('客户匹配结果较多，请继续输入更具体的关键词缩小范围')
    expect(wrapper.text()).toContain('SKU 匹配结果较多，请继续输入更具体的关键词缩小范围')

    vi.mocked(listCustomers).mockResolvedValueOnce(page([makeCustomer(102)]))
    vi.mocked(listSkus).mockResolvedValueOnce(page([makeSku(302)]))
    await remoteSearch(wrapper, 'order-customer-select', '后续客户')
    await remoteSearch(wrapper, 'order-sku-select', '后续 SKU')

    expect(listCustomers).toHaveBeenLastCalledWith({ page: 1, size: 20, keyword: '后续客户' })
    expect(listSkus).toHaveBeenLastCalledWith({ page: 1, size: 20, keyword: '后续 SKU', status: 'ACTIVE' })
  })

  it('新搜索不含已选记录时仍保留客户、SKU 与原价金额，可保存真实草稿', async () => {
    const wrapper = await openCreate()
    await choose(wrapper, 'order-customer-select', 42)
    await choose(wrapper, 'order-sku-select', 301)

    vi.mocked(listCustomers).mockResolvedValueOnce(page([makeCustomer(102)]))
    vi.mocked(listSkus).mockResolvedValueOnce(page([makeSku(302, 39900)]))
    await remoteSearch(wrapper, 'order-customer-select', '另一位客户')
    await remoteSearch(wrapper, 'order-sku-select', '另一款 SKU')

    expect(wrapper.text()).toContain('测试客户 42')
    expect(wrapper.text()).toContain('测试套餐 301')
    await wrapper.get('[data-testid="order-save-draft"]').trigger('click')
    await flushPromises()

    expect(createOrder).toHaveBeenCalledWith(expect.objectContaining({
      customerId: 42,
      payableFen: 19900,
      items: [{ skuId: 301, quantity: 1 }],
    }))
  })

  it('客户搜索失败保留已选项，重试沿用原关键词并清理错误', async () => {
    const wrapper = await openCreate()
    await choose(wrapper, 'order-customer-select', 42)
    vi.mocked(listCustomers).mockRejectedValueOnce(
      new ApiError({ status: 0, code: 'NETWORK_ERROR', message: '测试客户搜索失败' }),
    )

    await remoteSearch(wrapper, 'order-customer-select', '待重试客户')

    expect(wrapper.text()).toContain('客户搜索失败：测试客户搜索失败')
    expect(wrapper.text()).toContain('测试客户 42')
    vi.mocked(listCustomers).mockResolvedValueOnce(page([makeCustomer(102)]))
    await wrapper.get('[data-testid="customer-search-retry"]').trigger('click')
    await flushPromises()

    expect(listCustomers).toHaveBeenLastCalledWith({ page: 1, size: 20, keyword: '待重试客户' })
    expect(wrapper.text()).not.toContain('客户搜索失败：')
    expect(wrapper.text()).toContain('测试客户 42')
  })

  it('SKU 初次加载失败仍打开抽屉，可在原数据范围内重试并选择', async () => {
    vi.mocked(listSkus).mockRejectedValueOnce(
      new ApiError({ status: 0, code: 'NETWORK_ERROR', message: '测试 SKU 搜索失败' }),
    )
    const wrapper = await openCreate()

    expect(wrapper.text()).toContain('新建订单草稿')
    expect(wrapper.text()).toContain('上架 SKU 搜索失败：测试 SKU 搜索失败')
    expect(listCustomers).toHaveBeenCalledTimes(1)

    vi.mocked(listSkus).mockResolvedValueOnce(page([makeSku(302)]))
    await wrapper.get('[data-testid="sku-search-retry"]').trigger('click')
    await flushPromises()

    expect(listSkus).toHaveBeenLastCalledWith({ page: 1, size: 20, keyword: undefined, status: 'ACTIVE' })
    expect(wrapper.text()).not.toContain('上架 SKU 搜索失败：')
    await choose(wrapper, 'order-sku-select', 302)
    expect(wrapper.text()).toContain('测试套餐 302')
  })
})
